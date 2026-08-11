package com.deployhub.version.service;

import com.deployhub.version.entity.SubVersion;
import com.deployhub.version.entity.SubmitStatus;
import com.deployhub.version.repository.SubVersionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 패키징 가능 여부 판정. 조회 API와 매니페스트 확정이 함께 쓰므로 별도 클래스로 분리했다. */
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
