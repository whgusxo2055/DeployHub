package com.deployhub.version.service;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.version.dto.SubVersionSavedResponse;
import com.deployhub.version.dto.SubVersionUpsertRequest;
import com.deployhub.version.dto.SubmitStatusChangeRequest;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Version History 수동 등록·수정 (구현계획서 Phase 1-2). {@code PUT .../sub-versions}는
 * 요청에 포함된 code만 생성·수정한다(upsert) — 목록에서 빠진 기존 서브버전은 건드리지
 * 않는다. 삭제는 별도의 {@code DELETE /api/sub-versions/{id}}로만 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SubVersionService {

    private final MainVersionRepository mainVersionRepository;
    private final SubVersionRepository subVersionRepository;
    private final ComponentRepository componentRepository;
    private final ManifestLockGuard manifestLockGuard;

    public List<SubVersionSavedResponse> upsertAll(String versionName, List<SubVersionUpsertRequest> requests) {
        if (!mainVersionRepository.existsById(versionName)) {
            throw new ApiException(
                    ErrorCode.MAIN_VERSION_NOT_FOUND, "메인버전 '%s'을(를) 찾을 수 없습니다.".formatted(versionName));
        }
        manifestLockGuard.assertNotLocked(versionName);

        // code -> 이번 요청이 적용할 최종 image_tag 목록. 유일성 검증과 실제 저장이 같은 값을 쓴다.
        Map<String, List<String>> newTagsByCode = new HashMap<>();
        for (SubVersionUpsertRequest request : requests) {
            List<String> tags = resolveImageTags(request);
            newTagsByCode.put(request.code(), tags);
        }

        assertImageTagsUnique(versionName, newTagsByCode);

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

            List<Component> existingComponents = componentRepository.findBySubVersionIdOrderBySortOrderAsc(subVersion.getId());
            if (!existingComponents.isEmpty()) {
                componentRepository.deleteAll(existingComponents);
                // Hibernate는 같은 트랜잭션 내 INSERT를 DELETE보다 먼저 플러시한다.
                // 재생성되는 컴포넌트가 이전과 같은 image_tag를 쓰면 PK 충돌이 나므로 먼저 비운다.
                componentRepository.flush();
            }

            List<String> tags = newTagsByCode.get(request.code());
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

    public void delete(Long subVersionId) {
        SubVersion subVersion = subVersionRepository
                .findById(subVersionId)
                .orElseThrow(() -> new ApiException(ErrorCode.SUB_VERSION_NOT_FOUND));
        manifestLockGuard.assertNotLocked(subVersion.getMainVersionName());
        // component는 FK ON DELETE CASCADE로 함께 삭제된다 (V1__init_schema.sql).
        subVersionRepository.delete(subVersion);
    }

    public void changeSubmitStatus(Long subVersionId, SubmitStatusChangeRequest request) {
        SubVersion subVersion = subVersionRepository
                .findById(subVersionId)
                .orElseThrow(() -> new ApiException(ErrorCode.SUB_VERSION_NOT_FOUND));
        subVersion.changeSubmitStatus(request.status(), request.submittedBy());
    }

    /** 컴포넌트를 명시하지 않으면 {@code code:version} 1건을 자동 생성한다. */
    private List<String> resolveImageTags(SubVersionUpsertRequest request) {
        if (request.imageTags() == null || request.imageTags().isEmpty()) {
            return List.of("%s:%s".formatted(request.code(), request.version()));
        }
        return request.imageTags();
    }

    /**
     * 메인버전 내 image_tag 유일성을 검증한다(E-0203). 검증 범위는 이 메인버전으로 한정한다 —
     * 다른 메인버전이 같은 태그를 공유하는 것은 변경 여부 판정이 성립하기 위한 정상 상태다.
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
        Map<Long, String> codeBySubVersionId = subVersionRepository.findByMainVersionNameOrderBySortOrderAsc(versionName).stream()
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
        return new ApiException(
                ErrorCode.DUPLICATE_IMAGE_TAG,
                "image_tag '%s'가 서로 다른 서브버전(%s, %s)에 중복 지정되었습니다.".formatted(tag, codeA, codeB));
    }
}
