package com.deployhub.version.service;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.registry.ImageReference;
import com.deployhub.registry.ImageTagChecker;
import com.deployhub.registry.ImageTagChecker.TagCheck;
import com.deployhub.version.dto.SubVersionSavedResponse;
import com.deployhub.version.dto.SubVersionUpsertRequest;
import com.deployhub.version.entity.Component;
import com.deployhub.version.entity.SubVersion;
import com.deployhub.version.repository.ComponentRepository;
import com.deployhub.version.repository.MainVersionRepository;
import com.deployhub.version.repository.SubVersionRepository;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서브버전 등록·수정. <b>한 번에 한 담당 영역만</b> 다룬다 — 여러 건을 받으면 화면 전체를 실어 보내는
 * 구현을 유도해, 안 건드린 담당 영역이 되돌아가며 남의 저장분이 소실된다. 삭제는 id 경로로만 한다.
 */
@Slf4j
@Service
public class SubVersionService {

    private final MainVersionRepository mainVersionRepository;
    private final SubVersionRepository subVersionRepository;
    private final ComponentRepository componentRepository;
    private final ManifestLockGuard manifestLockGuard;
    private final SubVersionWriter subVersionWriter;
    private final ImageTagChecker imageTagChecker;
    // dev 프로필(더미 자격증명)과 레지스트리가 막힌 사내망에서 등록까지 죽지 않게 끌 수 있다.
    private final boolean verifyOnRegister;

    public SubVersionService(
            MainVersionRepository mainVersionRepository,
            SubVersionRepository subVersionRepository,
            ComponentRepository componentRepository,
            ManifestLockGuard manifestLockGuard,
            SubVersionWriter subVersionWriter,
            ImageTagChecker imageTagChecker,
            @Value("${deployhub.registry.verify-on-register:true}") boolean verifyOnRegister) {
        this.mainVersionRepository = mainVersionRepository;
        this.subVersionRepository = subVersionRepository;
        this.componentRepository = componentRepository;
        this.manifestLockGuard = manifestLockGuard;
        this.subVersionWriter = subVersionWriter;
        this.imageTagChecker = imageTagChecker;
        this.verifyOnRegister = verifyOnRegister;
    }

    /**
     * 레지스트리 조회가 DB 커넥션을 붙잡지 않게 순서를 나눈다: 형식 검사 → 레지스트리 확인 → 락 + 저장.
     * 저장 구간은 {@link SubVersionWriter}가 트랜잭션 안에서 맡는다.
     */
    public SubVersionSavedResponse upsert(String versionName, SubVersionUpsertRequest request) {
        if (!mainVersionRepository.existsById(versionName)) {
            throw new ApiException(ErrorCode.MAIN_VERSION_NOT_FOUND, List.of("versionName=" + versionName));
        }
        // 확정 잠금은 writer가 판정한다 — 상태만 갱신하는 요청은 확정 후에도 허용해야 해서,
        // "필드가 실제로 달라졌나"를 락 안에서 알기 전에는 막을 수 없다.
        List<String> tags = resolveImageTags(request);
        assertImageTagsExistInRegistry(unverifiedTags(versionName, request.code(), tags));

        return subVersionWriter.save(versionName, request, tags);
    }

    @Transactional
    public void delete(Long subVersionId) {
        SubVersion subVersion = subVersionRepository
                .findById(subVersionId)
                .orElseThrow(() -> new ApiException(ErrorCode.SUB_VERSION_NOT_FOUND));
        // 확정과 같은 행을 잡는다 — 없으면 assertNotLocked가 검사-행위 사이에 밀려, 확정된
        // package_item이 이미 삭제된 컴포넌트를 가리킨 채 패키징이 돈다.
        mainVersionRepository.lockByVersionName(subVersion.getMainVersionName());
        manifestLockGuard.assertNotLocked(subVersion.getMainVersionName());
        // component는 FK ON DELETE CASCADE로 함께 삭제된다.
        subVersionRepository.delete(subVersion);
    }

    /**
     * 컴포넌트(도커 이미지)가 없는 서브버전(예: EXT)도 정상 상태다 — imageTags를 비우면 컴포넌트를
     * 만들지 않는다. 값이 있으면 NCR REST 경로와 skopeo 인자로 그대로 들어가므로
     * {@link ImageReference#parse}로 여기서 문법을 강제한다.
     */
    private List<String> resolveImageTags(SubVersionUpsertRequest request) {
        List<String> tags = request.imageTags() == null ? List.of() : request.imageTags();
        // 같은 서브버전 안의 중복은 유일성 검사(서로 다른 code 기준)를 통과해버린다 —
        // 저장은 같은 PK로 두 번 일어나 1건이 되고 응답에는 2건이 실려 DB와 어긋난다.
        if (Set.copyOf(tags).size() != tags.size()) {
            throw new ApiException(ErrorCode.SUB_VERSION_VALIDATION_FAILED, List.of("code=" + request.code()));
        }
        for (String tag : tags) {
            try {
                ImageReference.parse(tag);
            } catch (IllegalArgumentException e) {
                // 예외 메시지에는 태그 원문이 들어 있다 — 로그로만 남기고 응답에는 태그만 싣는다.
                log.warn("image_tag 형식 오류: code={}, reason={}", request.code(), e.getMessage());
                throw new ApiException(ErrorCode.SUB_VERSION_VALIDATION_FAILED, List.of(tag));
            }
        }
        return tags;
    }

    /**
     * 이미 이 담당 영역에 저장된 태그는 등록 시점에 확인된 값이라 다시 묻지 않는다 — 안 그러면
     * "변경 없음" 한 번에 태그 수만큼 NCR 왕복이 붙는다(DEFERRED 8번).
     */
    private List<String> unverifiedTags(String versionName, String code, List<String> tags) {
        Set<String> stored = subVersionRepository
                .findByMainVersionNameAndCode(versionName, code)
                .map(subVersion -> componentRepository.findBySubVersionIdOrderBySortOrderAsc(subVersion.getId()))
                .orElse(List.of())
                .stream()
                .map(Component::getImageTag)
                .collect(Collectors.toSet());
        return tags.stream().filter(tag -> !stored.contains(tag)).toList();
    }

    /**
     * 레지스트리에 없는 태그를 등록 단계에서 막는다(E-0206). <b>404(확실히 없음)에만</b> 막는다 —
     * 타임아웃·차단은 "확인 불가"라 그걸로 막으면 레지스트리 장애가 등록 불가로 번진다.
     */
    private void assertImageTagsExistInRegistry(List<String> tags) {
        if (!verifyOnRegister) {
            return;
        }
        List<TagCheck> checks;
        try {
            checks = imageTagChecker.checkAll(tags);
        } catch (ApiException e) {
            // 자격증명 만료(401/403)까지 등록 불가로 번지면 안 된다 — 확인 불가로 보고 통과시키고,
            // 실제로 없는 태그라면 패키징 단계(E-0501)가 다시 잡는다.
            log.warn("등록 시점 레지스트리 확인을 건너뜁니다: errorCode={}", e.getErrorCode().getCode());
            return;
        }
        List<String> missing = checks.stream()
                .filter(TagCheck::definitelyMissing)
                .map(TagCheck::imageTag)
                .toList();
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.IMAGE_TAG_NOT_IN_REGISTRY, missing);
        }
    }
}
