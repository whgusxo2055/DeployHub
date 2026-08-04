package com.deployhub.version.service;

import com.deployhub.version.entity.SubVersion;
import com.deployhub.version.entity.SubmitStatus;
import com.deployhub.version.repository.SubVersionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FN-02-1 패키징 가능 여부 판정 (구현계획서 Phase 1-5). Phase 3의 매니페스트 확정이
 * 이 서비스를 재사용하므로 별도 클래스로 분리한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PackagingEligibilityService {

    private final SubVersionRepository subVersionRepository;

    /** 하나 이상의 담당 영역이 PENDING이면 패키징을 시작할 수 없다. */
    public PackagingEligibility evaluate(String versionName) {
        List<String> blockingCodes = subVersionRepository
                .findByMainVersionNameAndSubmitStatus(versionName, SubmitStatus.PENDING)
                .stream()
                .map(SubVersion::getCode)
                .toList();
        return new PackagingEligibility(blockingCodes.isEmpty(), blockingCodes);
    }
}
