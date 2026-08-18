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

    /**
     * 하나 이상의 담당 영역이 PENDING이면 패키징을 시작할 수 없다. 서브버전이 아예 없는 경우도
     * 마찬가지다 — "가능"으로 답해 놓고 확정에서 E-0303으로 막으면 화면이 원인을 못 보여준다.
     */
    public PackagingEligibility evaluate(String versionName) {
        List<SubVersion> subVersions = subVersionRepository.findByMainVersionNameOrderBySortOrderAsc(versionName);
        if (subVersions.isEmpty()) {
            return new PackagingEligibility(false, List.of());
        }
        List<String> blockingCodes = subVersions.stream()
                .filter(subVersion -> subVersion.getSubmitStatus() == SubmitStatus.PENDING)
                .map(SubVersion::getCode)
                .toList();
        return new PackagingEligibility(blockingCodes.isEmpty(), blockingCodes);
    }
}
