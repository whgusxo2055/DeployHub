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
 * FN-11 보존·정리의 <b>대상 선정</b>과 진입점 (구현계획서 Phase 6-2·6-3, 591-601행).
 * 두 단계가 기한이 다르다 — 중계 서버 작업 디렉터리는 업로드 완료 후
 * {@code LOCAL_CLEANUP_DELAY_HOURS}(기본 24시간), SharePoint 폴더는
 * {@code RETENTION_DAYS}(기본 90일)다.
 *
 * <p>실제 삭제는 전부 {@link PackagePurgeService}(건별 락 트랜잭션)에 위임한다 — 여기서
 * 뜬 목록은 스냅샷이라 그 사이 Job이 다시 실행됐을 수 있고, 그 재확인이 락 안에서
 * 이뤄져야 하기 때문이다. 이 클래스 자체는 트랜잭션을 열지 않는다.
 *
 * <p>{@code package_job}/{@code package_item} 행 자체는 지우지 않는다(597행) — 정리 후에도
 * Job 이력 조회가 가능해야 한다.
 */
@Slf4j
@Service
public class PackageCleanupService {

    /** 구현계획서 Phase 6-4 감사 로그. 대량 삭제 진입점이라 호출 자체를 남긴다. */
    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

    /**
     * SharePoint 정리 대상 상태. 구현계획서 594행의 조건에는 상태 제한이 없다 — FAILED로
     * 끝난 Job도 {@code GraphFolderService.ensureFolder}가 이미 폴더를 만들어 뒀을 수 있어,
     * DONE만 훑으면 그 폴더와 부분 업로드분이 영구히 남는다(코드리뷰로 발견).
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
        // 오타 하나(RETENTION_DAYS=-90)가 "기한 경과" 판정을 전건 통과시켜 전량 삭제로
        // 이어진다 — 복구 불가한 작업이라 기동에서 끊는다(UploadChunkSizeValidator 선례).
        // retentionCount가 음수면 alive.stream().skip()이 배치를 통째로 죽이기도 한다.
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
     * 일 1회 (구현계획서 591행). {@code zone}을 명시한다 — 컨테이너 기본 시간대가 UTC라
     * 빼면 "새벽 3시"가 KST 정오가 되어 업무 시간 중에 수 GB 삭제가 돈다(코드리뷰로 발견).
     * 배치가 실패해도 앱은 계속 떠 있어야 하므로 예외를 삼킨다.
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
     * {@code dryRun}이면 대상만 산출한다(구현계획서 598행). 한 건이 실패해도 나머지는 계속
     * 진행하고 실패 목록으로 돌려준다 — 폴더 하나의 권한 문제(E-1402)로 배치 전체가 멈추면
     * 안 된다.
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

        // 1. 중계 서버 정리 — 업로드가 끝난(DONE) Job만 대상이다(592행). FAILED Job의
        // 디렉터리는 재시도 여지를 남겨 두고, 2단계가 보존 기한(90일) 뒤에 함께 지운다.
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
                // false는 E-1403(삭제 실패)만 뜻한다 — 재실행 감지는 예외로 온다.
                failed.add(job.getVersionName());
            }
        }

        // 2. SharePoint 정리 — 보호 대상은 아직 살아 있는(deleted_at IS NULL) DONE Job 사이의
        // 순위로 정한다. 이미 정리된 Job은 내려받을 패키지가 없고, FAILED Job은 애초에
        // 배포 가능한 산출물이 아니라 "최근 N건 보관"의 실익이 없다.
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
            // 실제로 지운 것만 보고한다 — cleanupOne과 같은 규칙이다. 폴더가 없던 Job을
            // "정리 완료"로 세면 운영자가 상태를 오판한다.
            if (result.folderDeleted()) {
                sharePointCleaned.add(job.getVersionName());
            }
            if (!result.localDeleted()) {
                failed.add(job.getVersionName()); // E-1403 — 다음 배치에서 재시도한다.
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
     * 수동 정리 (구현계획서 Phase 6-3). 배치를 기다리지 않고 특정 Job의 패키지를 즉시
     * 지운다. 진행 중인 Job은 {@link PackagePurgeService#purge}가 E-1404로 거부한다 —
     * 여기서는 그 예외를 그대로 올려 409로 내보낸다.
     */
    public PackageCleanupResponse cleanupOne(String versionName) {
        // expectedFinishedAt=null — 운영자가 지금 상태를 보고 지시한 것이라 재실행 감지를
        // 걸지 않는다. 진행 중인 Job은 아래 purge가 상태로 거른다.
        PurgeResult result = packagePurgeService.purge(versionName, "manual", null);
        // 실제로 지운 것만 보고한다 — 로컬 삭제가 실패했거나(E-1403) 애초에 SharePoint
        // 폴더가 없던 Job을 "정리 완료"로 답하면 운영자가 상태를 오판한다(코드리뷰로 발견).
        return PackageCleanupResponse.builder()
                .dryRun(false)
                .localCleaned(result.localDeleted() ? List.of(versionName) : List.of())
                .sharePointCleaned(result.folderDeleted() ? List.of(versionName) : List.of())
                .failed(result.localDeleted() ? List.of() : List.of(versionName))
                .build();
    }

    /**
     * 배치 전용 — 건별 실패로 배치가 멈추지 않게 한다(구현계획서 611행). 대상 선정 이후 Job이
     * 다시 실행돼 건너뛴 경우(E-1404)는 실패가 아니므로 {@code failed}에 넣지 않는다.
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
            // E-1402(403) 등 — 해당 건만 건너뛰고 배치를 계속한다.
            log.warn("E-1402: 패키지 정리를 건너뜁니다: versionName={}, reason={}", job.getVersionName(), e.toString());
            failed.add(job.getVersionName());
            return null;
        }
    }

    private boolean isOlderThan(PackageJob job, Duration age, Instant now) {
        return job.getFinishedAt().plus(age).isBefore(now);
    }
}
