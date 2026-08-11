package com.deployhub.version.service;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.job.repository.PackageJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 확정된 매니페스트 보호 — package_job이 있고 FAILED가 아니면 서브버전 수정·삭제를 막는다. */
@Service
@RequiredArgsConstructor
public class ManifestLockGuard {

    private final PackageJobRepository packageJobRepository;

    public void assertNotLocked(String versionName) {
        packageJobRepository.findById(versionName).ifPresent(job -> {
            if (job.blocksManifestModification()) {
                throw new ApiException(
                        ErrorCode.MANIFEST_LOCKED,
                        "메인버전 '%s'은(는) 이미 패키징 Job(%s)이 있어 수정할 수 없습니다."
                                .formatted(versionName, job.getStatus()));
            }
        });
    }
}
