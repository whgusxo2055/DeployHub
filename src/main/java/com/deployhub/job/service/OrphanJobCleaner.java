package com.deployhub.job.service;

import com.deployhub.job.entity.JobStatus;
import com.deployhub.job.entity.PackageJob;
import com.deployhub.job.repository.PackageJobRepository;
import java.util.EnumSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기동 시 고아 Job 정리. 강제 종료되면 진행 중 상태로 Job이 남아 영구히 좌초하므로 FAILED로 되돌린다.
 * PENDING도 반드시 포함할 것 — 빼면 그 메인버전은 {@code force}로도 복구할 수 없다.
 * ponytail: 기동 직후 짧은 창에서 막 생성된 정상 PENDING을 잘못 집을 수 있으나,
 * FAILED는 재시도가 가능해 영구 좌초보다 나은 실패 모드라 별도 가드를 두지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanJobCleaner implements ApplicationRunner {

    private static final EnumSet<JobStatus> ORPHAN_STATUSES = EnumSet.of(
            JobStatus.PENDING, JobStatus.VALIDATING, JobStatus.DOWNLOADING, JobStatus.UPLOADING);

    private final PackageJobRepository packageJobRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<PackageJob> orphans = packageJobRepository.findByStatusInOrderByCreatedAtDesc(ORPHAN_STATUSES);
        for (PackageJob job : orphans) {
            log.warn(
                    "E-1501 고아 Job을 FAILED로 정리합니다: versionName={}, status={}",
                    job.getVersionName(),
                    job.getStatus());
            job.changeStatus(JobStatus.FAILED);
        }
    }
}
