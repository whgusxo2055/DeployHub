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
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.FileSystemUtils;

/**
 * FN-11 정리의 <b>건별 실행 단위</b>. 대상 선정은 {@link PackageCleanupService}가 하고,
 * 실제 삭제는 전부 여기를 거친다.
 *
 * <p>별도 빈으로 뺀 이유는 트랜잭션 경계다. 삭제는 반드시 <b>행 락을 쥔 채로</b> 상태를
 * 다시 확인하고 실행해야 한다 — 대상 선정 시점의 상태를 믿고 지우면, 그 사이
 * {@code POST .../package-job (force=true)}나 {@code POST .../retry}가 같은 메인버전을
 * 다시 돌리기 시작한 뒤에 작업 디렉터리를 통째로 날린다(실행 중인 skopeo가 원인 불명으로
 * 죽고, 살아 있는 Job에 {@code deleted_at}이 찍혀 {@code resolveJob}의 {@code alreadyCleaned}
 * 판정까지 오염된다 — 코드리뷰·보안 리뷰가 함께 지적). {@code PackageCleanupService} 안에
 * 두면 배치 루프의 자기 호출({@code this.purge(...)})이 프록시를 거치지 않아
 * {@code @Transactional}과 락이 조용히 무시된다.
 *
 * <p>트레이드오프 — Graph DELETE(재시도 백오프 포함)와 수 GB 재귀 삭제를 트랜잭션 안에서
 * 한다. 전체 배치가 아니라 <b>건당</b> 트랜잭션이고 하루 한 번 수 건 규모라 감당 범위다.
 * ponytail: 락 유지 시간이 곧 천장이다 — 같은 메인버전에 API 요청이 겹치면
 * {@code innodb_lock_wait_timeout}(기본 50초)까지 대기한다. 실측에서 문제가 되면 Graph
 * DELETE와 로컬 삭제를 락 밖으로 빼고 트랜잭션은 재확인 + {@code deleted_at} 기록만 남긴다
 * (대신 삭제 도중 재실행을 막을 다른 수단이 필요해진다).
 */
@Slf4j
@Service
public class PackagePurgeService {

    /** 구현계획서 Phase 6-4 감사 로그. 삭제는 복구 불가라 실행 계기(trigger)를 반드시 남긴다. */
    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

    /** 정리 가능한 종료 상태. 그 외(PENDING/VALIDATING/DOWNLOADING/UPLOADING)는 진행 중이라 건드리지 않는다. */
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
     * 중계 서버 정리(배치 1단계) — 작업 디렉터리만 지운다. SharePoint 폴더는 그대로 두므로
     * {@code deleted_at}도 찍지 않는다. 업로드가 끝난 Job만 대상이라 {@code DONE}을 요구한다
     * (구현계획서 592행 "업로드 전 단계 Job은 제외").
     *
     * @return 디렉터리가 사라졌으면 true. false는 E-1403(삭제 실패)만 뜻한다 — 그 사이
     *     재실행된 경우는 {@code PACKAGE_CLEANUP_BLOCKED}로 던져 배치가 실패와 구분하게 한다.
     */
    @Transactional
    public boolean purgeLocal(String versionName, String trigger, Instant expectedFinishedAt) {
        PackageJob job = lockJob(versionName);
        if (job.getStatus() != JobStatus.DONE) {
            throw rerunDetected(versionName, "status=" + job.getStatus());
        }
        assertNotRerun(job, expectedFinishedAt);
        return deleteLocalDir(versionName, trigger);
    }

    /**
     * SharePoint 폴더 + 작업 디렉터리를 지우고 {@code deleted_at}을 남긴다(배치 2단계, 수동 정리).
     * 진행 중인 Job은 E-1404로 거부한다 — 배치는 이 예외를 "그 사이 재실행됨"으로 보고
     * 건너뛰고, 수동 정리 API는 그대로 409로 내보낸다.
     *
     * <p>404(E-1401)는 {@link GraphApiClient#delete}가 이미 성공으로 처리한다 — 이미 사라진
     * 폴더도 {@code deleted_at}을 남겨 다음 배치가 같은 건을 다시 집지 않게 한다.
     *
     * @param expectedFinishedAt 배치가 대상을 고른 시점의 {@code finished_at}. 다르면 그 사이
     *     재실행이 끝난 것이라 거부한다. 수동 정리는 운영자가 지금 상태를 보고 지시한
     *     것이므로 {@code null}을 넘겨 이 검사를 건너뛴다.
     */
    @Transactional
    public PurgeResult purge(String versionName, String trigger, Instant expectedFinishedAt) {
        PackageJob job = lockJob(versionName);
        if (!TERMINAL.contains(job.getStatus())) {
            throw new ApiException(
                    ErrorCode.PACKAGE_CLEANUP_BLOCKED,
                    "진행 중인 Job(%s)의 패키지는 정리할 수 없습니다.".formatted(job.getStatus()));
        }
        assertNotRerun(job, expectedFinishedAt);

        String folderId = job.getSpFolderId();
        boolean folderDeleted = folderId != null && !folderId.isBlank();
        if (folderDeleted) {
            graphApiClient.delete("/drives/%s/items/%s".formatted(graphApiClient.resolveDriveId(), folderId));
        }
        boolean localDeleted = deleteLocalDir(versionName, trigger);
        // 디렉터리를 못 지웠으면(E-1403) deleted_at을 찍지 않는다 — 찍으면 alive 필터에서
        // 빠져 다음 배치가 이 건을 다시 집지 못하고 디렉터리가 영구히 남는다. FAILED Job은
        // 1단계(DONE 전용)도 안 타므로 회수 경로가 아예 사라진다. Graph DELETE는 404를
        // 성공으로 흡수하므로 다음 배치가 통째로 재시도해도 무해하다.
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
     * 대상 선정은 배치 진입 시 뜬 스냅샷이고, 그 뒤 건별 삭제(수 GB 재귀 삭제 + Graph
     * 재시도 백오프)가 이어져 배치 1회가 길어질 수 있다. 그 사이 {@code force=true} 재생성이나
     * {@code /retry}가 같은 메인버전을 다시 돌려 <b>끝까지 완료</b>하면 상태는 여전히 DONE이라
     * {@code TERMINAL} 검사를 통과한다 — 방금 만들어진 폴더와 디렉터리가 지워지고
     * {@code RETENTION_COUNT} 보호도 스냅샷 순위라 함께 뚫린다. 상태가 아니라
     * {@code finished_at}이 그대로인지를 봐야 "그 사이 다시 돌았는지"를 잡는다.
     */
    private ApiException rerunDetected(String versionName, String detail) {
        log.info("정리를 건너뜁니다 — 그 사이 재실행됨: versionName={}, {}", versionName, detail);
        return new ApiException(
                ErrorCode.PACKAGE_CLEANUP_BLOCKED, "그 사이 재실행되어 정리 대상이 아닙니다(%s).".formatted(detail));
    }

    private void assertNotRerun(PackageJob job, Instant expectedFinishedAt) {
        if (expectedFinishedAt != null && !expectedFinishedAt.equals(job.getFinishedAt())) {
            throw rerunDetected(
                    job.getVersionName(), "finishedAt %s -> %s".formatted(expectedFinishedAt, job.getFinishedAt()));
        }
    }

    /**
     * 락 없이 {@code findById}로 먼저 조회하지 않는다 — Hibernate가 1차 캐시의 stale
     * 인스턴스를 그대로 돌려줘 락이 무력화된다(CLAUDE.md 코드 패턴, {@code PackageJobService.resolveJob} 참고).
     */
    private PackageJob lockJob(String versionName) {
        return packageJobRepository
                .findByVersionName(versionName)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.PACKAGE_JOB_NOT_FOUND, "메인버전 '%s'의 패키지 Job이 없습니다.".formatted(versionName)));
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
        // E-1403 — 다음 배치에서 다시 시도한다(구현계획서 611행). 상태를 따로 남기지 않아도
        // 조건(DONE + 유예 경과 + 디렉터리 존재)이 그대로라 자연히 재선정된다.
        log.warn("E-1403: 작업 디렉터리 삭제에 실패했습니다. 다음 배치에서 재시도합니다: {}", dir);
        return false;
    }

    /**
     * {@code versionName}은 DB를 거쳐 오고 {@code main_version}의 등록 정규식이 유일한
     * 진입점이라 지금은 {@code ..}가 섞일 수 없다. 그럼에도 여기만 <b>재귀 삭제</b>라
     * 다른 소비자({@code PackageDownloadService} 등)보다 오판의 대가가 크다 — 명명 규칙이
     * 느슨해지는 미래를 대비해 삭제 직전에 한 번 더 막는다
     * ({@code GraphFolderService.validateFolderName}과 같은 이유, 보안 리뷰로 발견).
     */
    private Path jobDir(String versionName) {
        Path base = Path.of(workDir).toAbsolutePath().normalize();
        Path dir = base.resolve(versionName).normalize();
        if (!base.equals(dir.getParent())) {
            throw new IllegalStateException("작업 디렉터리 경로가 work-dir을 벗어납니다: " + versionName);
        }
        return dir;
    }

    /** 무엇이 실제로 지워졌는지 — 수동 정리 API 응답이 "정리 완료"를 과장하지 않게 한다. */
    public record PurgeResult(boolean localDeleted, boolean folderDeleted) {}
}
