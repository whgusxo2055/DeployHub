package com.deployhub.job.service;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.job.entity.PackageItem;
import com.deployhub.job.entity.PackageItemStatus;
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
 * 생성 요청이 이 결과를 기다리므로 여기서 던지면 Job이 만들어지지 않은 것과 같다 — 그래서 레지스트리가
 * 404로 "없다"고 답한 것만 중단시키고, 타임아웃·연결 실패는 다운로드 단계의 즉석 조회에 맡긴다.
 * 401/403은 발견 즉시 새어나가 남은 항목을 확인하지 않는다({@link ImageTagChecker}에서 던진다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackageValidationService {

    private final PackageItemRepository packageItemRepository;
    private final ImageTagChecker imageTagChecker;

    public Map<String, ManifestInfo> validate(String versionName) {
        // PENDING만 본다 — 생성 직후에는 전 항목이, 재시도에서는 되돌린 항목만 PENDING이라
        // 두 경로가 같은 규칙으로 "아직 처리 안 된 것"만 확인하게 된다.
        List<PackageItem> items = packageItemRepository.findByVersionNameOrderByImageTagAsc(versionName).stream()
                .filter(item -> item.getStatus() == PackageItemStatus.PENDING)
                .toList();
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
            // 레지스트리가 404로 "없다"고 답한 것만 생성을 막는다 — 타임아웃·연결 실패까지 막으면
            // 사내망 차단 상황에서 Job 생성 자체가 불가능해진다(서브버전 등록 E-0206과 같은 기준).
            if (!check.definitelyMissing()) {
                continue;
            }
            item.markFailed(check.failureCode());
            missing.add(item);
        }

        if (!missing.isEmpty()) {
            packageItemRepository.saveAll(missing);
            // 생성 요청이 이 예외를 그대로 400으로 내보낸다 — 실패 태그는 details로만 싣고
            // 항목별 사유는 이미 package_item.error_message에 있다.
            throw new ApiException(
                    ErrorCode.IMAGE_TAG_MISSING_IN_REGISTRY,
                    missing.stream().map(PackageItem::getImageTag).toList());
        }
        return context;
    }
}
