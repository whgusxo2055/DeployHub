package com.deployhub.job.service;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.job.dto.PackageCleanupResponse;
import com.deployhub.job.entity.JobStatus;
import com.deployhub.job.entity.PackageJob;
import com.deployhub.job.repository.PackageJobRepository;
import com.deployhub.job.service.PackagePurgeService.PurgeResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 보존·정리의 <b>대상 선정</b>과 진입점. 두 단계의 기한이 다르다 — 작업 디렉터리는
 * {@code LOCAL_CLEANUP_DELAY_HOURS}, SharePoint 폴더는 {@code RETENTION_DAYS}다.
 * 실제 삭제는 전부 {@link PackagePurgeService}(건별 락 트랜잭션)에 위임한다 — 여기 목록은 스냅샷이라
 * 재확인이 락 안에서 이뤄져야 한다. 이력 조회를 위해 {@code package_job} 행 자체는 지우지 않는다.
 */
@Slf4j
@Service
public class PackageCleanupService {

    /** 감사 로그. 대량 삭제 진입점이라 호출 자체를 남긴다. */
    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

    /**
     * SharePoint 정리 대상 상태. FAILED도 포함해야 한다 — 폴더 확보 후 실패한 Job의 폴더와
     * 부분 업로드분이 DONE만 훑으면 영구히 남는다.
     */
    private static final Set<JobStatus> CLEANABLE = EnumSet.of(JobStatus.DONE, JobStatus.FAILED);

    private final PackageJobRepository packageJobRepository;
    private final PackagePurgeService packagePurgeService;
    private final Duration localCleanupDelay;
    private final Duration retention;
    private final int retentionCount;

    public PackageCleanupService(
            PackageJobRepository packageJobRepository,
            PackagePurgeService packagePurgeService,
            @Value("${deployhub.retention.local-cleanup-delay-hours:24}") int localCleanupDelayHours,
            @Value("${deployhub.retention.days:90}") int retentionDays,
            @Value("${deployhub.retention.count:10}") int retentionCount) {
        this.packageJobRepository = packageJobRepository;
        this.packagePurgeService = packagePurgeService;
        // 오타 하나(RETENTION_DAYS=-90)가 "기한 경과"를 전건 통과시켜 전량 삭제로 이어진다 —
        // 복구 불가라 기동에서 끊는다.
        if (retentionDays < 1 || retentionCount < 0 || localCleanupDelayHours < 0) {
            throw new IllegalStateException(
                    "보존 정책 설정이 올바르지 않습니다: days=%d(1 이상), count=%d(0 이상), localCleanupDelayHours=%d(0 이상)"
                            .formatted(retentionDays, retentionCount, localCleanupDelayHours));
        }
        this.localCleanupDelay = Duration.ofHours(localCleanupDelayHours);
        this.retention = Duration.ofDays(retentionDays);
        this.retentionCount = retentionCount;
    }

    /**
     * 일 1회. {@code zone}을 반드시 명시할 것 — 컨테이너 기본 시간대가 UTC라 빼면
     * "새벽 3시"가 KST 정오가 되어 업무 시간에 수 GB 삭제가 돈다. 앱이 죽지 않게 예외는 삼킨다.
     */
    @Scheduled(cron = "${deployhub.retention.cron:0 0 3 * * *}", zone = "Asia/Seoul")
    public void scheduledCleanup() {
        try {
            PackageCleanupResponse result = cleanup(false, "scheduled");
            log.info(
                    "FN-11 정리 배치 완료: local={}, sharePoint={}, failed={}",
                    result.localCleaned(),
                    result.sharePointCleaned(),
                    result.failed());
        } catch (RuntimeException e) {
            log.error("FN-11 정리 배치가 실패했습니다. 다음 실행에서 재시도합니다.", e);
        }
    }

    /**
     * {@code dryRun}이면 대상만 산출한다. 한 건이 실패해도 나머지를 계속 진행하고 실패 목록으로 돌려준다 —
     * 폴더 하나의 권한 문제로 배치 전체가 멈추면 안 된다.
     */
    public PackageCleanupResponse cleanup(boolean dryRun, String trigger) {
        Instant now = Instant.now();
        // finished_at DESC 정렬이 곧 "최근 RETENTION_COUNT건 보호"의 기준이다.
        List<PackageJob> candidates = packageJobRepository.findByStatusInOrderByFinishedAtDesc(CLEANABLE).stream()
                .filter(job -> job.getFinishedAt() != null)
                .toList();

        List<String> localCleaned = new ArrayList<>();
        List<String> sharePointCleaned = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        // 1단계 — DONE만 대상이다. FAILED의 디렉터리는 재시도 여지를 남기고 2단계가 기한 뒤에 함께 지운다.
        for (PackageJob job : candidates) {
            if (job.getStatus() != JobStatus.DONE || !isOlderThan(job, localCleanupDelay, now)) {
                continue;
            }
            if (dryRun) {
                localCleaned.add(job.getVersionName());
                continue;
            }
            Boolean deleted = runQuietly(
                    job, failed, () -> packagePurgeService.purgeLocal(job.getVersionName(), trigger, job.getFinishedAt()));
            if (Boolean.TRUE.equals(deleted)) {
                localCleaned.add(job.getVersionName());
            } else if (deleted != null) {
                failed.add(job.getVersionName()); // false는 삭제 실패만 뜻한다 — 재실행 감지는 예외로 온다
            }
        }

        // 2단계 — 보호 대상은 아직 살아 있는 DONE Job 사이의 순위로 정한다.
        // 정리된 Job은 내려받을 게 없고, FAILED는 배포 가능한 산출물이 아니라 보관 실익이 없다.
        List<PackageJob> alive =
                candidates.stream().filter(job -> job.getDeletedAt() == null).toList();
        Set<String> protectedNames = alive.stream()
                .filter(job -> job.getStatus() == JobStatus.DONE)
                .limit(retentionCount)
                .map(PackageJob::getVersionName)
                .collect(Collectors.toSet());

        for (PackageJob job : alive) {
            if (protectedNames.contains(job.getVersionName()) || !isOlderThan(job, retention, now)) {
                continue;
            }
            if (dryRun) {
                sharePointCleaned.add(job.getVersionName());
                continue;
            }
            PurgeResult result = runQuietly(
                    job, failed, () -> packagePurgeService.purge(job.getVersionName(), trigger, job.getFinishedAt()));
            if (result == null) {
                continue;
            }
            // 실제로 지운 것만 보고한다 — 폴더가 없던 Job을 "정리 완료"로 세면 운영자가 상태를 오판한다.
            if (result.folderDeleted()) {
                sharePointCleaned.add(job.getVersionName());
            }
            if (!result.localDeleted()) {
                failed.add(job.getVersionName()); // 다음 배치에서 재시도한다
            } else if (!localCleaned.contains(job.getVersionName())) {
                localCleaned.add(job.getVersionName());
            }
        }

        AUDIT.info(
                "cleanup-batch trigger={} dryRun={} local={} sharePoint={} failed={}",
                trigger,
                dryRun,
                localCleaned,
                sharePointCleaned,
                failed);
        return PackageCleanupResponse.builder()
                .dryRun(dryRun)
                .localCleaned(localCleaned)
                .sharePointCleaned(sharePointCleaned)
                .failed(failed)
                .build();
    }

    /**
     * 수동 정리 — 배치를 기다리지 않고 즉시 지운다. 진행 중인 Job은 {@link PackagePurgeService#purge}가
     * 거부하고, 그 예외를 그대로 올려 409로 내보낸다.
     */
    public PackageCleanupResponse cleanupOne(String versionName) {
        // expectedFinishedAt=null — 운영자가 지금 상태를 보고 지시한 것이라 재실행 감지를 걸지 않는다.
        PurgeResult result = packagePurgeService.purge(versionName, "manual", null);
        // 실제로 지운 것만 보고한다 — 과장하면 운영자가 상태를 오판한다.
        return PackageCleanupResponse.builder()
                .dryRun(false)
                .localCleaned(result.localDeleted() ? List.of(versionName) : List.of())
                .sharePointCleaned(result.folderDeleted() ? List.of(versionName) : List.of())
                .failed(result.localDeleted() ? List.of() : List.of(versionName))
                .build();
    }

    /**
     * 배치 전용 — 건별 실패로 배치가 멈추지 않게 한다. 그 사이 재실행돼 건너뛴 경우는
     * 실패가 아니므로 {@code failed}에 넣지 않는다.
     *
     * @return 예외로 건너뛰었으면 null. 그 외에는 {@code action}의 반환값 그대로다.
     */
    private <T> T runQuietly(PackageJob job, List<String> failed, Supplier<T> action) {
        try {
            return action.get();
        } catch (ApiException e) {
            if (e.getErrorCode() == ErrorCode.PACKAGE_CLEANUP_BLOCKED) {
                return null; // 사유는 PackagePurgeService가 이미 로그로 남겼다.
            }
            log.warn(
                    "패키지 정리를 건너뜁니다: versionName={}, code={}",
                    job.getVersionName(),
                    e.getErrorCode().getCode());
            failed.add(job.getVersionName());
            return null;
        } catch (RuntimeException e) {
            // 권한 문제(403) 등 — 해당 건만 건너뛰고 배치를 계속한다.
            log.warn("E-1402: 패키지 정리를 건너뜁니다: versionName={}, reason={}", job.getVersionName(), e.toString());
            failed.add(job.getVersionName());
            return null;
        }
    }

    private boolean isOlderThan(PackageJob job, Duration age, Instant now) {
        return job.getFinishedAt().plus(age).isBefore(now);
    }
}
