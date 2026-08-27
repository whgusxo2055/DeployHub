package com.deployhub.job.service;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.job.entity.PackageItem;
import com.deployhub.job.entity.PackageItemStatus;
import com.deployhub.job.repository.PackageItemRepository;
import com.deployhub.registry.ImageTagChecker;
import com.deployhub.registry.ImageTagChecker.TagCheck;
import com.deployhub.registry.NcrRegistryClient.ManifestInfo;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 항목별 매니페스트를 조회해 digest·크기를 메모리 컨텍스트로 반환한다(DB에 담지 않는다).
 * 레지스트리가 404로 "없다"고 답한 것만 중단시키고, 타임아웃·연결 실패는 다운로드 단계의 즉석
 * 조회에 맡긴다 — 사내망 차단 중에 Job 생성 자체가 불가능해지면 안 된다(등록 E-0206과 같은 기준).
 * 401/403은 발견 즉시 새어나가 남은 항목을 확인하지 않는다({@link ImageTagChecker}에서 던진다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackageValidationService {

    private final PackageItemRepository packageItemRepository;
    private final ImageTagChecker imageTagChecker;

    /** 생성 경로 — Job 행이 아직 없다. 실패는 400으로 끝나고 아무것도 남기지 않는다. */
    public Map<String, ManifestInfo> validate(List<String> imageTags) {
        Checked checked = check(imageTags);
        if (!checked.missing().isEmpty()) {
            throw new ApiException(ErrorCode.IMAGE_TAG_MISSING_IN_REGISTRY, List.copyOf(checked.missing().keySet()));
        }
        return checked.context();
    }

    /**
     * 재시도 경로 — 확정 당시엔 있던 태그가 그 사이 지워진 경우라 항목에 사유를 남긴다.
     * 생성과 달리 거절하는 대상이 사용자 입력이 아니라 이미 저장된 상태다.
     */
    public Map<String, ManifestInfo> validatePendingItems(String versionName) {
        // PENDING만 본다 — 생성 직후에는 전 항목이, 재시도에서는 되돌린 항목만 PENDING이다.
        List<PackageItem> items = packageItemRepository.findByVersionNameOrderByImageTagAsc(versionName).stream()
                .filter(item -> item.getStatus() == PackageItemStatus.PENDING)
                .toList();

        Checked checked = check(items.stream().map(PackageItem::getImageTag).toList());
        if (checked.missing().isEmpty()) {
            return checked.context();
        }

        List<PackageItem> failed = items.stream()
                .filter(item -> checked.missing().containsKey(item.getImageTag()))
                .toList();
        failed.forEach(item -> item.markFailed(checked.missing().get(item.getImageTag())));
        packageItemRepository.saveAll(failed);
        // 실패 태그는 details로만 싣는다 — 항목별 사유는 이미 package_item.error_message에 있다.
        throw new ApiException(ErrorCode.IMAGE_TAG_MISSING_IN_REGISTRY, List.copyOf(checked.missing().keySet()));
    }

    private Checked check(List<String> imageTags) {
        // 결과 순서에 기대지 않고 태그로 짝짓는다 — 순서가 어긋나면 다른 항목의 digest가 붙어
        // 다운로드 직후 무결성 대조(E-0603)가 엉뚱하게 터진다.
        Map<String, ManifestInfo> context = new HashMap<>();
        Map<String, ErrorCode> missing = new LinkedHashMap<>();

        for (TagCheck check : imageTagChecker.checkAll(imageTags)) {
            if (check.found()) {
                context.put(check.imageTag(), check.manifestInfo());
                continue;
            }
            log.warn("항목 검증 실패: imageTag={}, reason={}", check.imageTag(), check.failureCode().getCode());
            if (check.definitelyMissing()) {
                missing.put(check.imageTag(), check.failureCode());
            }
        }
        return new Checked(context, missing);
    }

    private record Checked(Map<String, ManifestInfo> context, Map<String, ErrorCode> missing) {}
}
