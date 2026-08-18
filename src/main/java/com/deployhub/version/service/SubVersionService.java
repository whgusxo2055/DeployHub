package com.deployhub.version.service;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.registry.ImageReference;
import com.deployhub.registry.ImageTagChecker;
import com.deployhub.registry.ImageTagChecker.TagCheck;
import com.deployhub.version.dto.SubVersionSavedResponse;
import com.deployhub.version.dto.SubVersionUpsertRequest;
import com.deployhub.version.dto.SubmitStatusChangeRequest;
import com.deployhub.version.entity.Component;
import com.deployhub.version.entity.SubVersion;
import com.deployhub.version.repository.ComponentRepository;
import com.deployhub.version.repository.MainVersionRepository;
import com.deployhub.version.repository.SubVersionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서브버전 등록·수정. 요청에 포함된 code만 upsert하고 목록에서 빠진 기존 서브버전은 건드리지 않는다 —
 * 삭제는 {@code DELETE /api/sub-versions/{id}}로만 한다.
 */
@Slf4j
@Service
public class SubVersionService {

    private final MainVersionRepository mainVersionRepository;
    private final SubVersionRepository subVersionRepository;
    private final ManifestLockGuard manifestLockGuard;
    private final SubVersionWriter subVersionWriter;
    private final ImageTagChecker imageTagChecker;
    // dev 프로필(더미 자격증명)과 레지스트리가 막힌 사내망에서 등록까지 죽지 않게 끌 수 있다.
    private final boolean verifyOnRegister;

    public SubVersionService(
            MainVersionRepository mainVersionRepository,
            SubVersionRepository subVersionRepository,
            ManifestLockGuard manifestLockGuard,
            SubVersionWriter subVersionWriter,
            ImageTagChecker imageTagChecker,
            @Value("${deployhub.registry.verify-on-register:true}") boolean verifyOnRegister) {
        this.mainVersionRepository = mainVersionRepository;
        this.subVersionRepository = subVersionRepository;
        this.manifestLockGuard = manifestLockGuard;
        this.subVersionWriter = subVersionWriter;
        this.imageTagChecker = imageTagChecker;
        this.verifyOnRegister = verifyOnRegister;
    }

    /**
     * 저장 자체는 {@link SubVersionWriter}가 트랜잭션 안에서 한다 — 레지스트리 조회가 DB 커넥션을
     * 붙잡지 않게 순서를 이렇게 나눈다: 형식·중복 검사 → 레지스트리 확인 → 락 + 저장.
     */
    public List<SubVersionSavedResponse> upsertAll(String versionName, List<SubVersionUpsertRequest> requests) {
        if (!mainVersionRepository.existsById(versionName)) {
            throw new ApiException(ErrorCode.MAIN_VERSION_NOT_FOUND, List.of("versionName=" + versionName));
        }
        // 빠른 실패용 — 확정적인 판정은 writer가 락을 잡은 뒤 다시 한다.
        manifestLockGuard.assertNotLocked(versionName);

        Map<String, List<String>> newTagsByCode = resolveTagsByCode(requests);
        assertImageTagsExistInRegistry(newTagsByCode);

        return subVersionWriter.save(versionName, requests, newTagsByCode);
    }

    /**
     * code -> 최종 image_tag 목록. 유일성 검증과 실제 저장이 같은 값을 쓰게 한다.
     *
     * <p>같은 배치에 code가 두 번 오면 거절한다 — Map에 담는 구조라 뒤엣것이 앞엣것을 조용히
     * 덮어쓰는데, 저장 루프는 요청 목록을 그대로 돌아 <b>응답에 남의 태그가 실린다</b>.
     */
    private Map<String, List<String>> resolveTagsByCode(List<SubVersionUpsertRequest> requests) {
        Map<String, List<String>> newTagsByCode = new LinkedHashMap<>();
        for (SubVersionUpsertRequest request : requests) {
            if (newTagsByCode.putIfAbsent(request.code(), resolveImageTags(request)) != null) {
                throw new ApiException(
                        ErrorCode.SUB_VERSION_VALIDATION_FAILED, List.of("code=" + request.code(), "duplicated"));
            }
        }
        return newTagsByCode;
    }

    @Transactional
    public void delete(Long subVersionId) {
        SubVersion subVersion = subVersionRepository
                .findById(subVersionId)
                .orElseThrow(() -> new ApiException(ErrorCode.SUB_VERSION_NOT_FOUND));
        manifestLockGuard.assertNotLocked(subVersion.getMainVersionName());
        // component는 FK ON DELETE CASCADE로 함께 삭제된다.
        subVersionRepository.delete(subVersion);
    }

    /** 매니페스트 구성을 바꾸지 않으므로 {@link ManifestLockGuard}를 타지 않는다(확정 후에도 상태 갱신은 허용). */
    @Transactional
    public void changeSubmitStatus(Long subVersionId, SubmitStatusChangeRequest request) {
        SubVersion subVersion = subVersionRepository
                .findById(subVersionId)
                .orElseThrow(() -> new ApiException(ErrorCode.SUB_VERSION_NOT_FOUND));
        subVersion.changeSubmitStatus(request.status());
    }

    /**
     * 컴포넌트를 명시하지 않으면 {@code code:version} 1건을 자동 생성한다. 어느 경로든
     * {@link ImageReference#parse}로 문법을 강제한다 — 이 값이 그대로 NCR REST 경로와 skopeo
     * 인자로 들어가므로, 자유 문자열이 들어오는 이 지점에서 막아야 뒤 단계로 흘러가지 않는다.
     */
    private List<String> resolveImageTags(SubVersionUpsertRequest request) {
        List<String> tags = (request.imageTags() == null || request.imageTags().isEmpty())
                ? List.of("%s:%s".formatted(request.code(), request.version()))
                : request.imageTags();
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
     * 레지스트리에 없는 image_tag를 등록 단계에서 막는다(E-0206). 배포 파이프라인이 이미지를 밀어
     * 올린 뒤에 버전 히스토리를 적는 운영 순서라, 여기서 없다는 건 사실상 오타다 — 패키징까지 가서
     * E-0501로 드러나는 것보다 등록자에게 즉시 돌려주는 편이 낫다.
     *
     * <p><b>404(확실히 없음)에만 막는다</b> — 타임아웃·차단은 "확인 불가"이지 "없음"이 아니라서,
     * 그걸로 막으면 레지스트리 장애가 등록 불가로 번진다. 그 경우는 패키징 단계에서 다시 걸린다.
     *
     * <p>ponytail: 태그 수에 비례해 DB 트랜잭션 안에서 동기로 기다린다(동시 {@code manifest.concurrency}건).
     * 사람이 하루 몇 번 누르는 등록이라 감수한다 — 커넥션 점유가 문제가 되면 트랜잭션 밖으로 뺄 것.
     */
    private void assertImageTagsExistInRegistry(Map<String, List<String>> newTagsByCode) {
        if (!verifyOnRegister) {
            return;
        }
        List<String> tags = newTagsByCode.values().stream()
                .flatMap(List::stream)
                .distinct()
                .sorted()
                .toList();
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
