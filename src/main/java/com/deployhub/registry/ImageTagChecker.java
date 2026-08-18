package com.deployhub.registry;

import com.deployhub.common.ApiException;
import com.deployhub.common.BoundedParallelism;
import com.deployhub.common.ErrorCode;
import com.deployhub.common.ItemErrorCode;
import com.deployhub.registry.NcrRegistryClient.ManifestInfo;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * image_tag가 레지스트리에 있는지 확인한다. 서브버전 등록(차단)과 Job 실행 중 검증(VALIDATING)이
 * 같은 판정을 쓰도록 한 곳에 둔다 — 갈라지면 "등록은 됐는데 패키징만 E-0501"이 된다.
 */
@Slf4j
@Service
public class ImageTagChecker {

    private final NcrRegistryClient ncrRegistryClient;
    private final Executor manifestExecutor;
    // manifestExecutor 풀 크기와 반드시 같아야 한다 — 같은 프로퍼티를 읽어 어긋날 수 없게 한다.
    private final int concurrency;

    public ImageTagChecker(
            NcrRegistryClient ncrRegistryClient,
            @Qualifier("manifestExecutor") Executor manifestExecutor,
            @Value("${deployhub.manifest.concurrency:5}") int concurrency) {
        this.ncrRegistryClient = ncrRegistryClient;
        this.manifestExecutor = manifestExecutor;
        this.concurrency = concurrency;
    }

    /**
     * 결과는 입력 순서를 유지한다. 401/403만 그대로 던진다 — 자격증명 문제를 "이미지 없음"으로
     * 뭉개면 운영자가 원인을 못 찾는다. 나머지 레지스트리 오류는 "확인 불가"라 항목 실패로 강등한다.
     */
    public List<TagCheck> checkAll(List<String> imageTags) {
        return BoundedParallelism.mapInBatches(imageTags, concurrency, manifestExecutor, this::checkOne);
    }

    private TagCheck checkOne(String imageTag) {
        ImageReference ref;
        try {
            ref = ImageReference.parse(imageTag);
        } catch (IllegalArgumentException e) {
            // 예외 메시지에는 태그 원문이 들어 있다 — 코드가 정한 문구만 남기고 원문은 로그로.
            log.warn("image_tag 형식 오류: reason={}", e.getMessage());
            return new TagCheck(imageTag, null, ItemErrorCode.INVALID_IMAGE_TAG, false);
        }

        try {
            Optional<ManifestInfo> manifest = ncrRegistryClient.getManifest(ref);
            return manifest.map(info -> new TagCheck(imageTag, info, null, false))
                    .orElseGet(() -> new TagCheck(imageTag, null, ItemErrorCode.IMAGE_NOT_FOUND, true));
        } catch (ApiException e) {
            // 사내망 차단은 토큰 응답에 평문이 끼어 REGISTRY_UNREACHABLE로 분류된다 —
            // 이걸 그대로 던지면 "차단은 등록을 막지 않는다"는 약속이 정작 차단 상황에서 깨지고,
            // 인덱스 이미지 하나가 형제 태그 전체의 검증까지 중단시킨다. 401/403만 올린다.
            if (e.getErrorCode() == ErrorCode.REGISTRY_UNAUTHORIZED) {
                throw e;
            }
            log.warn("레지스트리 확인 실패({}) — 누락 처리합니다: imageTag={}", e.getErrorCode().getCode(), imageTag);
            return new TagCheck(
                    imageTag,
                    null,
                    e.getErrorCode() == ErrorCode.REGISTRY_TIMEOUT
                            ? ItemErrorCode.MANIFEST_LOOKUP_TIMEOUT
                            : ItemErrorCode.MANIFEST_LOOKUP_UNAVAILABLE,
                    false);
        }
    }

    /**
     * {@code definitelyMissing}은 레지스트리가 404로 "없다"고 답한 경우만 참이다 — 타임아웃·형식
     * 오류와 구분해야 등록 차단이 네트워크 장애로 번지지 않는다.
     *
     * <p>{@code failureCode}의 문구가 무인증 응답에 그대로 실린다({@link ItemErrorCode} 참고).
     */
    public record TagCheck(
            String imageTag, ManifestInfo manifestInfo, ItemErrorCode failureCode, boolean definitelyMissing) {

        public boolean found() {
            return manifestInfo != null;
        }
    }
}
