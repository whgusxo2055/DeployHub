package com.deployhub.version.service;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.version.dto.SubVersionSavedResponse;
import com.deployhub.version.dto.SubVersionUpsertRequest;
import com.deployhub.version.entity.Component;
import com.deployhub.version.entity.MainVersion;
import com.deployhub.version.entity.SubVersion;
import com.deployhub.version.entity.SubmitStatus;
import com.deployhub.version.repository.ComponentRepository;
import com.deployhub.version.repository.MainVersionRepository;
import com.deployhub.version.repository.SubVersionRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * upsert의 <b>트랜잭션 구간</b>. 분리한 이유는 둘 — 레지스트리 조회를 트랜잭션 밖에 두려고(같은 빈
 * 안에서 나눠 부르면 프록시를 안 탄다), 그리고 "매니페스트 확정"과 같은 행에 락을 잡으려고.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SubVersionWriter {

    private final MainVersionRepository mainVersionRepository;
    private final SubVersionRepository subVersionRepository;
    private final ComponentRepository componentRepository;
    private final ManifestLockGuard manifestLockGuard;

    public SubVersionSavedResponse save(String versionName, SubVersionUpsertRequest request, List<String> tags) {
        // 락을 먼저 잡는다 — 그 뒤의 검사와 저장이 하나의 직렬 구간이 된다. 경로 문자열이 아니라
        // 조회된 정규 이름을 쓴다 — FK 대조가 ai_ci라 전각·ZWSP 표기가 그대로 저장될 수 있다.
        String canonical = mainVersionRepository
                .lockByVersionName(versionName)
                .map(MainVersion::getVersionName)
                .orElseThrow(() ->
                        new ApiException(ErrorCode.MAIN_VERSION_NOT_FOUND, List.of("versionName=" + versionName)));
        assertImageTagsUnique(canonical, request.code(), tags);

        try {
            return upsert(canonical, request, tags);
        } catch (DataIntegrityViolationException e) {
            // uk_sub_version_main_code / component PK 충돌. 락으로 대부분 막히지만 남는 경합은
            // 500(E-9000)이 아니라 "다시 시도하세요"로 돌려준다.
            throw new ApiException(ErrorCode.SUB_VERSION_SAVE_CONFLICT, List.of("versionName=" + versionName));
        }
    }

    private SubVersionSavedResponse upsert(String versionName, SubVersionUpsertRequest request, List<String> tags) {
        SubVersion existing =
                subVersionRepository.findByMainVersionNameAndCode(versionName, request.code()).orElse(null);
        boolean isNew = existing == null;
        SubVersion subVersion = isNew
                ? subVersionRepository.save(SubVersion.builder()
                        .mainVersionName(versionName)
                        .code(request.code())
                        .version(request.version())
                        .note(request.note())
                        .sortOrder(request.sortOrder())
                        .build())
                : existing;

        List<Component> existingComponents =
                componentRepository.findBySubVersionIdOrderBySortOrderAsc(subVersion.getId());
        // 순서 변경도 변경이다 — 여기서 놓치면 제출한 적 없는 컴포넌트로 패키징이 통과한다.
        boolean changed = isNew
                || subVersion.update(request.version(), request.note(), request.sortOrder())
                || !existingComponents.stream().map(Component::getImageTag).toList().equals(tags);

        // 값이 달라졌는데 "변경 없음"이면 요청자가 stale한 화면을 보고 있다는 뜻이다.
        if (changed && request.submitStatus() == SubmitStatus.UNCHANGED) {
            throw new ApiException(
                    ErrorCode.SUB_VERSION_STATUS_CONTRADICTION, List.of("code=" + request.code()));
        }
        // 매니페스트를 실제로 건드릴 때만 확정 잠금을 적용한다 — 상태만 갱신하는 요청은 확정 후에도 허용한다.
        if (changed) {
            manifestLockGuard.assertNotLocked(versionName);
        }
        subVersion.changeSubmitStatus(request.submitStatus());

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

        return SubVersionSavedResponse.builder()
                .id(subVersion.getId())
                .code(subVersion.getCode())
                .version(subVersion.getVersion())
                .note(subVersion.getNote())
                .sortOrder(subVersion.getSortOrder())
                .imageTags(tags)
                .build();
    }

    /**
     * 메인버전 내 image_tag 유일성(E-0203). DB 제약으로 못 걸어(PK가 서브버전을 못 가로지른다) 위
     * 행 락이 원자성을 대신한다. 요청 안의 중복은 {@code resolveImageTags}가 이미 거부했다.
     */
    private void assertImageTagsUnique(String versionName, String code, List<String> tags) {
        Set<String> newTags = Set.copyOf(tags);
        Map<Long, String> codeBySubVersionId =
                subVersionRepository.findByMainVersionNameOrderBySortOrderAsc(versionName).stream()
                        .collect(Collectors.toMap(SubVersion::getId, SubVersion::getCode));

        for (Component existing : componentRepository.findByMainVersionName(versionName)) {
            String ownerCode = codeBySubVersionId.get(existing.getSubVersionId());
            if (ownerCode == null || ownerCode.equals(code)) {
                // 이번 요청이 재생성할 컴포넌트이므로 비교 대상에서 제외한다.
                continue;
            }
            if (newTags.contains(existing.getImageTag())) {
                throw new ApiException(
                        ErrorCode.DUPLICATE_IMAGE_TAG,
                        List.of(existing.getImageTag(), "code=" + ownerCode, "code=" + code));
            }
        }
    }
}
