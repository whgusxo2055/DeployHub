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
 * 기동 시 고아 Job 정리 (구현계획서 417행, E-1501). 워커가 강제 종료되면 PENDING(아직
 * 워커에 제출되지 못했거나 실행기 큐에서 대기 중이던 Job — {@code waitForTasksToCompleteOnShutdown}을
 * 켜지 않아 재기동하면 사라진다)이나 VALIDATING/DOWNLOADING/UPLOADING 상태로 Job이 남을
 * 수 있어 재기동 시 FAILED로 정리한다. PENDING을 빼면 그 메인버전은 {@code force}로도
 * 영원히 복구할 수 없다({@code resolveJob}이 FAILED 외 진행 상태는 항상 차단하기 때문).
 * {@code deployhub.startup-checks.enabled}로 끄지 않는다 — StartupChecks(외부 연동
 * 도달성 확인)와 달리 이 정리는 DB만 다루고 항상 안전하게 돌 수 있다.
 *
 * <p>이론상 기동 직후 아주 짧은 창에서 막 생성된 정상 PENDING을 잘못 정리할 가능성이
 * 있으나(임베디드 서버가 요청을 받기 시작하는 시점과 이 러너가 도는 시점의 순서는
 * Boot 버전에 따라 달라질 수 있다), 정리돼도 FAILED로 남아 재시도가 가능하므로 영구
 * 좌초보다 훨씬 나은 실패 모드다 — 별도 가드를 두지 않는다.
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
