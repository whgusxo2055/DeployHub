package com.deployhub.job.service;

import com.deployhub.common.ItemErrorCode;
import com.deployhub.job.entity.PackageItem;
import com.deployhub.job.repository.PackageItemRepository;
import com.deployhub.registry.ImageTagChecker;
import com.deployhub.registry.ImageTagChecker.TagCheck;
import com.deployhub.registry.NcrRegistryClient.ManifestInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * VALIDATING 단계 — 항목별 매니페스트를 조회해 digest·크기를 메모리 컨텍스트로 반환한다(DB에 담지 않는다).
 * 404·타임아웃은 해당 항목만 실패시키고 끝까지 확인한 뒤 1건이라도 있으면 전체를 중단하지만,
 * 401/403은 발견 즉시 새어나가 남은 항목을 확인하지 않는다({@link ImageTagChecker}에서 던진다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackageValidationService {

    private final PackageItemRepository packageItemRepository;
    private final ImageTagChecker imageTagChecker;

    public Map<String, ManifestInfo> validate(String versionName) {
        List<PackageItem> items = packageItemRepository.findByVersionNameOrderByImageTagAsc(versionName);
        // 입력 순서가 그대로 유지되므로 인덱스로 짝지어도 안전하다(BoundedParallelism).
        List<TagCheck> checks =
                imageTagChecker.checkAll(items.stream().map(PackageItem::getImageTag).toList());

        List<PackageItem> missing = new ArrayList<>();
        Map<String, ManifestInfo> context = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            PackageItem item = items.get(i);
            TagCheck check = checks.get(i);
            if (check.found()) {
                context.put(item.getImageTag(), check.manifestInfo());
                continue;
            }
            log.warn(
                    "항목 검증 실패: versionName={}, imageTag={}, reason={}",
                    versionName,
                    item.getImageTag(),
                    check.failureCode().getCode());
            item.markFailed(check.failureCode().toErrorMessage());
            missing.add(item);
        }

        if (!missing.isEmpty()) {
            packageItemRepository.saveAll(missing);
            throw new IllegalStateException(
                    "E-0501/E-0503: 존재하지 않거나 누락 간주된 항목이 %d건 있어 다운로드를 시작하지 않습니다."
                            .formatted(missing.size()));
        }
        return context;
    }
}
