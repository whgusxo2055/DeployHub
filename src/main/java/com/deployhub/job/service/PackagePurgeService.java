package com.deployhub.job.service;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.job.entity.JobStatus;
import com.deployhub.job.entity.PackageJob;
import com.deployhub.job.repository.PackageJobRepository;
import com.deployhub.sharepoint.GraphApiClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileSystemUtils;

/**
 * 정리의 건별 실행 단위. 대상 선정은 {@link PackageCleanupService}가 하고 실제 삭제는 전부 여기를 거친다.
 * 별도 빈인 이유는 트랜잭션 경계다 — 삭제는 <b>행 락을 쥔 채</b> 상태를 재확인해야 하는데,
 * 같은 클래스 안이면 배치 루프의 자기 호출이 프록시를 안 거쳐 {@code @Transactional}과 락이 조용히 무시된다.
 *
 * <p>ponytail: Graph DELETE와 수 GB 재귀 삭제를 락 안에서 한다 — 락 유지 시간이 천장이라
 * 같은 메인버전에 API 요청이 겹치면 {@code innodb_lock_wait_timeout}까지 대기한다.
 * 문제가 되면 삭제를 락 밖으로 빼고 트랜잭션은 재확인 + {@code deleted_at} 기록만 남길 것.
 */
@Slf4j
@Service
public class PackagePurgeService {

    /** 감사 로그. 삭제는 복구 불가라 실행 계기(trigger)를 반드시 남긴다. */
    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

    /** 정리 가능한 종료 상태. 그 외는 진행 중이라 건드리지 않는다. */
    private static final Set<JobStatus> TERMINAL = EnumSet.of(JobStatus.DONE, JobStatus.FAILED);

    private final PackageJobRepository packageJobRepository;
    private final GraphApiClient graphApiClient;
    private final String workDir;

    public PackagePurgeService(
            PackageJobRepository packageJobRepository,
            GraphApiClient graphApiClient,
            @Value("${deployhub.work-dir}") String workDir) {
        this.packageJobRepository = packageJobRepository;
        this.graphApiClient = graphApiClient;
        this.workDir = workDir;
    }

    /**
     * 배치 1단계 — 작업 디렉터리만 지운다. SharePoint 폴더는 남으므로 {@code deleted_at}도 찍지 않는다.
     *
     * @return 디렉터리가 사라졌으면 true. false는 삭제 실패만 뜻한다 — 그 사이 재실행된 경우는
     *     {@code PACKAGE_CLEANUP_BLOCKED}로 던져 배치가 실패와 구분하게 한다.
     */
    @Transactional
    public boolean purgeLocal(String versionName, String trigger, Instant expectedFinishedAt) {
        PackageJob job = packageJobRepository.lockOrThrow(versionName);
        if (job.getStatus() != JobStatus.DONE) {
            throw rerunDetected(versionName, "status=" + job.getStatus());
        }
        assertNotRerun(job, expectedFinishedAt);
        return deleteLocalDir(versionName, trigger);
    }

    /**
     * 배치 2단계·수동 정리 — SharePoint 폴더와 작업 디렉터리를 지우고 {@code deleted_at}을 남긴다.
     * 진행 중인 Job은 거부한다(배치는 "그 사이 재실행됨"으로 건너뛰고, 수동 정리는 409로 나간다).
     *
     * @param expectedFinishedAt 대상 선정 시점의 {@code finished_at}. 다르면 그 사이 재실행이
     *     끝난 것이라 거부한다. 수동 정리는 {@code null}로 이 검사를 건너뛴다.
     */
    @Transactional
    public PurgeResult purge(String versionName, String trigger, Instant expectedFinishedAt) {
        PackageJob job = packageJobRepository.lockOrThrow(versionName);
        if (!TERMINAL.contains(job.getStatus())) {
            throw new ApiException(
                    ErrorCode.PACKAGE_CLEANUP_BLOCKED, List.of("status=" + job.getStatus()));
        }
        assertNotRerun(job, expectedFinishedAt);

        String folderId = job.getSpFolderId();
        boolean folderDeleted = folderId != null && !folderId.isBlank();
        if (folderDeleted) {
            graphApiClient.delete("/drives/%s/items/%s".formatted(graphApiClient.resolveDriveId(), folderId));
        }
        boolean localDeleted = deleteLocalDir(versionName, trigger);
        // 디렉터리를 못 지웠으면 deleted_at을 찍지 않는다 — 찍으면 alive 필터에서 빠져
        // 다음 배치가 이 건을 다시 집지 못하고 디렉터리가 영구히 남는다.
        if (localDeleted) {
            job.markDeleted();
        }

        AUDIT.info(
                "package-purge versionName={} trigger={} spFolderId={} folderDeleted={} localDeleted={} jobCreatedBy={}",
                versionName,
                trigger,
                folderId,
                folderDeleted,
                localDeleted,
                job.getCreatedBy());
        return new PurgeResult(localDeleted, folderDeleted);
    }

    /**
     * 대상 선정은 스냅샷이라 그 사이 재실행이 <b>끝까지 완료</b>되면 상태는 여전히 DONE이고
     * {@code TERMINAL} 검사를 통과한다 — 방금 만든 폴더가 지워진다.
     * 상태가 아니라 {@code finished_at}이 그대로인지를 봐야 재실행을 잡는다.
     */
    private ApiException rerunDetected(String versionName, String detail) {
        log.info("정리를 건너뜁니다 — 그 사이 재실행됨: versionName={}, {}", versionName, detail);
        return new ApiException(ErrorCode.PACKAGE_CLEANUP_RERUN, List.of(detail));
    }

    private void assertNotRerun(PackageJob job, Instant expectedFinishedAt) {
        if (expectedFinishedAt != null && !expectedFinishedAt.equals(job.getFinishedAt())) {
            throw rerunDetected(
                    job.getVersionName(), "finishedAt %s -> %s".formatted(expectedFinishedAt, job.getFinishedAt()));
        }
    }

    private boolean deleteLocalDir(String versionName, String trigger) {
        Path dir = jobDir(versionName);
        if (!Files.exists(dir)) {
            return true;
        }
        if (FileSystemUtils.deleteRecursively(dir.toFile())) {
            AUDIT.info("local-purge versionName={} trigger={} dir={}", versionName, trigger, dir);
            return true;
        }
        // 다음 배치에서 다시 시도한다 — 조건(DONE + 유예 경과 + 디렉터리 존재)이 그대로라 자연히 재선정된다.
        log.warn("E-1403: 작업 디렉터리 삭제에 실패했습니다. 다음 배치에서 재시도합니다: {}", dir);
        return false;
    }

    /**
     * 여기만 <b>재귀 삭제</b>라 경로 오판의 대가가 크다 — 등록 정규식이 이미 {@code ..}를 막지만
     * 그 검증이 느슨해지는 미래를 대비해 삭제 직전에 한 번 더 막는다.
     */
    private Path jobDir(String versionName) {
        Path base = Path.of(workDir).toAbsolutePath().normalize();
        Path dir = base.resolve(versionName).normalize();
        if (!base.equals(dir.getParent())) {
            throw new IllegalStateException("작업 디렉터리 경로가 work-dir을 벗어납니다: " + versionName);
        }
        return dir;
    }

    /** 무엇이 실제로 지워졌는지 — 응답이 "정리 완료"를 과장하지 않게 한다. */
    public record PurgeResult(boolean localDeleted, boolean folderDeleted) {}
}
