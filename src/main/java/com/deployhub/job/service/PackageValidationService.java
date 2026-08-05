package com.deployhub.job.service;

import com.deployhub.common.ApiException;
import com.deployhub.common.BoundedParallelism;
import com.deployhub.common.ErrorCode;
import com.deployhub.job.entity.PackageItem;
import com.deployhub.job.repository.PackageItemRepository;
import com.deployhub.registry.ImageReference;
import com.deployhub.registry.NcrRegistryClient;
import com.deployhub.registry.NcrRegistryClient.ManifestInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * FN-05 산출물 존재 여부 확인 (구현계획서 453-465행, VALIDATING 단계). 항목별로 레지스트리
 * 매니페스트를 조회해 digest·크기를 "Job 실행 컨텍스트(메모리)"로 반환한다 — DB에 보관하지
 * 않는다(0.4절 FN-05). 404(E-0501)·타임아웃(E-0503)은 해당 항목만 실패 처리하고 나머지는
 * 계속 확인하지만, 확인이 다 끝난 뒤 1건이라도 있으면 전체를 중단시킨다. 401/403(E-0502)은
 * 발견 즉시 {@link ApiException}이 그대로 새어나가 배치 전체를 중단한다 — 남은 항목은
 * 확인하지 않는다.
 */
@Slf4j
@Service
public class PackageValidationService {

    private static final int MANIFEST_CHECK_CONCURRENCY = 5;

    private final PackageItemRepository packageItemRepository;
    private final NcrRegistryClient ncrRegistryClient;
    private final Executor manifestExecutor;

    public PackageValidationService(
            PackageItemRepository packageItemRepository,
            NcrRegistryClient ncrRegistryClient,
            @Qualifier("manifestExecutor") Executor manifestExecutor) {
        this.packageItemRepository = packageItemRepository;
        this.ncrRegistryClient = ncrRegistryClient;
        this.manifestExecutor = manifestExecutor;
    }

    public Map<String, ManifestInfo> validate(String versionName) {
        List<PackageItem> items = packageItemRepository.findByVersionNameOrderByImageTagAsc(versionName);
        List<CheckOutcome> outcomes =
                BoundedParallelism.mapInBatches(items, MANIFEST_CHECK_CONCURRENCY, manifestExecutor, this::checkItem);

        List<CheckOutcome> missing = outcomes.stream().filter(outcome -> !outcome.found()).toList();
        if (!missing.isEmpty()) {
            for (CheckOutcome outcome : missing) {
                log.warn(
                        "항목 검증 실패: versionName={}, imageTag={}, reason={}",
                        versionName,
                        outcome.item().getImageTag(),
                        outcome.failureMessage());
                outcome.item().markFailed(outcome.failureMessage());
            }
            packageItemRepository.saveAll(missing.stream().map(CheckOutcome::item).toList());
            throw new IllegalStateException(
                    "E-0501/E-0503: 존재하지 않거나 누락 간주된 항목이 %d건 있어 다운로드를 시작하지 않습니다."
                            .formatted(missing.size()));
        }

        Map<String, ManifestInfo> context = new HashMap<>();
        for (CheckOutcome outcome : outcomes) {
            context.put(outcome.item().getImageTag(), outcome.manifestInfo());
        }
        return context;
    }

    private CheckOutcome checkItem(PackageItem item) {
        ImageReference ref;
        try {
            ref = ImageReference.parse(item.getImageTag());
        } catch (IllegalArgumentException e) {
            return new CheckOutcome(item, null, "E-0501: image_tag 형식이 올바르지 않습니다: " + e.getMessage());
        }

        try {
            Optional<ManifestInfo> manifest = ncrRegistryClient.getManifest(ref.repository(), ref.tag());
            return manifest.map(info -> new CheckOutcome(item, info, null))
                    .orElseGet(() -> new CheckOutcome(item, null, "E-0501: 레지스트리에 이미지가 존재하지 않습니다."));
        } catch (ApiException e) {
            if (e.getErrorCode() == ErrorCode.REGISTRY_TIMEOUT) {
                return new CheckOutcome(item, null, "E-0503: 레지스트리 응답 시간 초과로 누락 처리했습니다.");
            }
            // REGISTRY_UNAUTHORIZED 등은 그대로 던져 검증 전체를 중단시킨다(E-0502) —
            // 여기서 항목 실패로 삼키면 남은 항목을 계속 확인해버린다.
            throw e;
        }
    }

    private record CheckOutcome(PackageItem item, ManifestInfo manifestInfo, String failureMessage) {
        boolean found() {
            return manifestInfo != null;
        }
    }
}
