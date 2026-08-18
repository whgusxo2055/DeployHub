package com.deployhub.version.service;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.version.dto.SubVersionSavedResponse;
import com.deployhub.version.dto.SubVersionUpsertRequest;
import com.deployhub.version.entity.Component;
import com.deployhub.version.entity.SubVersion;
import com.deployhub.version.repository.ComponentRepository;
import com.deployhub.version.repository.MainVersionRepository;
import com.deployhub.version.repository.SubVersionRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서브버전 upsert의 <b>트랜잭션 구간</b>. {@link SubVersionService}에서 분리한 이유는 두 가지다.
 *
 * <p>① 레지스트리 조회(외부 HTTP, 최악 수십 초)를 트랜잭션 밖에 두기 위해서다. 같은 빈 안에서
 * 메서드를 나눠 불러도 프록시를 타지 않아 {@code @Transactional}이 걸리지 않는다.
 *
 * <p>② 진입 즉시 {@code main_version} 행에 비관적 락을 잡는다. 이 락은
 * {@code PackageJobService.create}와 <b>같은 행</b>을 대상으로 해서, "매니페스트 확정"과
 * "컴포넌트 수정"이 서로를 앞지르지 못하게 한다 — 없으면 두 트랜잭션이 각자 검사를 통과해
 * 확정된 매니페스트와 DB 매니페스트가 어긋난 채 패키징이 돈다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SubVersionWriter {

    private final MainVersionRepository mainVersionRepository;
    private final SubVersionRepository subVersionRepository;
    private final ComponentRepository componentRepository;
    private final ManifestLockGuard manifestLockGuard;

    public List<SubVersionSavedResponse> save(
            String versionName, List<SubVersionUpsertRequest> requests, Map<String, List<String>> newTagsByCode) {
        // 락을 먼저 잡는다 — 그 뒤의 검사와 저장이 하나의 직렬 구간이 된다.
        mainVersionRepository
                .lockByVersionName(versionName)
                .orElseThrow(() ->
                        new ApiException(ErrorCode.MAIN_VERSION_NOT_FOUND, List.of("versionName=" + versionName)));
        // 락 획득 전에 Job이 생겼을 수 있어 여기서 한 번 더 본다(진입 시 검사는 빠른 실패용).
        manifestLockGuard.assertNotLocked(versionName);
        assertImageTagsUnique(versionName, newTagsByCode);

        try {
            return upsert(versionName, requests, newTagsByCode);
        } catch (DataIntegrityViolationException e) {
            // uk_sub_version_main_code / component PK 충돌. 락으로 대부분 막히지만 남는 경합은
            // 500(E-9000)이 아니라 "다시 시도하세요"로 돌려준다.
            throw new ApiException(ErrorCode.SUB_VERSION_SAVE_CONFLICT, List.of("versionName=" + versionName));
        }
    }

    private List<SubVersionSavedResponse> upsert(
            String versionName, List<SubVersionUpsertRequest> requests, Map<String, List<String>> newTagsByCode) {
        List<SubVersionSavedResponse> responses = new ArrayList<>();
        for (SubVersionUpsertRequest request : requests) {
            SubVersion subVersion = subVersionRepository
                    .findByMainVersionNameAndCode(versionName, request.code())
                    .map(existing -> {
                        existing.update(request.version(), request.note(), request.sortOrder());
                        return existing;
                    })
                    .orElseGet(() -> subVersionRepository.save(SubVersion.builder()
                            .mainVersionName(versionName)
                            .code(request.code())
                            .version(request.version())
                            .note(request.note())
                            .sortOrder(request.sortOrder())
                            .build()));

            List<Component> existingComponents =
                    componentRepository.findBySubVersionIdOrderBySortOrderAsc(subVersion.getId());
            List<String> tags = newTagsByCode.get(request.code());
            // 컴포넌트만 바뀐 수정은 SubVersion.update가 "변경 없음"으로 보고 제출 상태를 그대로 둔다 —
            // 여기서 되돌리지 않으면 제출한 적 없는 컴포넌트로 패키징이 통과한다(순서 변경도 변경이다).
            if (!existingComponents.stream().map(Component::getImageTag).toList().equals(tags)) {
                subVersion.resetSubmitStatus();
            }
            if (!existingComponents.isEmpty()) {
                componentRepository.deleteAll(existingComponents);
                // Hibernate는 같은 트랜잭션 내 INSERT를 DELETE보다 먼저 플러시한다 —
                // 같은 image_tag를 다시 쓰면 PK 충돌이 나므로 먼저 비운다.
                componentRepository.flush();
            }

            int order = 0;
            for (String tag : tags) {
                componentRepository.save(Component.builder()
                        .subVersionId(subVersion.getId())
                        .imageTag(tag)
                        .sortOrder(order++)
                        .build());
            }

            responses.add(SubVersionSavedResponse.builder()
                    .id(subVersion.getId())
                    .code(subVersion.getCode())
                    .version(subVersion.getVersion())
                    .note(subVersion.getNote())
                    .sortOrder(subVersion.getSortOrder())
                    .imageTags(tags)
                    .build());
        }
        return responses;
    }

    /**
     * 메인버전 내 image_tag 유일성을 검증한다(E-0203). 검증 범위는 이 메인버전으로 한정한다 —
     * 다른 메인버전이 같은 태그를 공유하는 것은 변경 여부 판정이 성립하기 위한 정상 상태다.
     *
     * <p>DB에는 이 제약이 없다(PK가 {@code (sub_version_id, image_tag)}라 서브버전을 가로지르지
     * 못한다) — 그래서 위 행 락이 이 검사의 원자성을 대신 보장한다.
     */
    private void assertImageTagsUnique(String versionName, Map<String, List<String>> newTagsByCode) {
        Map<String, String> tagOwner = new HashMap<>();

        for (Map.Entry<String, List<String>> entry : newTagsByCode.entrySet()) {
            for (String tag : entry.getValue()) {
                String existingOwner = tagOwner.putIfAbsent(tag, entry.getKey());
                if (existingOwner != null && !existingOwner.equals(entry.getKey())) {
                    throw duplicateTagException(tag, existingOwner, entry.getKey());
                }
            }
        }

        Set<String> touchedCodes = newTagsByCode.keySet();
        Map<Long, String> codeBySubVersionId =
                subVersionRepository.findByMainVersionNameOrderBySortOrderAsc(versionName).stream()
                        .collect(Collectors.toMap(SubVersion::getId, SubVersion::getCode));

        for (Component existing : componentRepository.findByMainVersionName(versionName)) {
            String ownerCode = codeBySubVersionId.get(existing.getSubVersionId());
            if (ownerCode == null || touchedCodes.contains(ownerCode)) {
                // 이번 배치가 재생성할 컴포넌트이므로 비교 대상에서 제외한다.
                continue;
            }
            String existingOwner = tagOwner.putIfAbsent(existing.getImageTag(), ownerCode);
            if (existingOwner != null && !existingOwner.equals(ownerCode)) {
                throw duplicateTagException(existing.getImageTag(), existingOwner, ownerCode);
            }
        }
    }

    private ApiException duplicateTagException(String tag, String codeA, String codeB) {
        return new ApiException(ErrorCode.DUPLICATE_IMAGE_TAG, List.of(tag, "code=" + codeA, "code=" + codeB));
    }
}
