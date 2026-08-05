package com.deployhub.job.service;

import com.deployhub.job.entity.JobStatus;
import com.deployhub.registry.NcrRegistryClient.ManifestInfo;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Job 단계 순차 실행 (구현계획서 Phase 3-2, Phase 4). {@code @Transactional}을 붙이지 않는다 —
 * 긴 트랜잭션을 피하고, 폴링 클라이언트가 단계 중간 상태를 볼 수 있어야 한다.
 *
 * <p>{@link #start(String)}은 VALIDATING부터, {@link #resume(String)}은 FN-07 수동
 * 재시도 전용 진입점으로 DOWNLOADING부터 재개한다 — "이미 DOWNLOADED인 항목은 재다운로드
 * 하지 않는다"(구현계획서 489행)는 요구를 만족시키려 매번 VALIDATING을 다시 돌지 않는다.
 * 그 대신 재시도 시 매니페스트 컨텍스트가 없으므로 {@link PackageDownloadService}가 항목별로
 * 즉석에서 다시 조회한다({@code manifestContext}가 비어 있을 때의 동작 참고).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobOrchestrator {

    private final PackageJobService packageJobService;
    private final PackageValidationService packageValidationService;
    private final PackageDownloadService packageDownloadService;

    @Async("jobExecutor")
    public void start(String versionName) {
        try {
            packageJobService.changeStatus(versionName, JobStatus.VALIDATING);
            Map<String, ManifestInfo> manifestContext = packageValidationService.validate(versionName);

            proceedFromDownloading(versionName, manifestContext);
        } catch (Exception e) {
            handleFailure(versionName, e);
        }
    }

    @Async("jobExecutor")
    public void resume(String versionName) {
        try {
            proceedFromDownloading(versionName, Map.of());
        } catch (Exception e) {
            handleFailure(versionName, e);
        }
    }

    private void proceedFromDownloading(String versionName, Map<String, ManifestInfo> manifestContext) {
        packageJobService.changeStatus(versionName, JobStatus.DOWNLOADING);
        packageDownloadService.download(versionName, manifestContext);

        packageJobService.changeStatus(versionName, JobStatus.UPLOADING);
        upload(versionName);

        packageJobService.changeStatus(versionName, JobStatus.DONE);
    }

    // @Async void 메서드 밖으로 예외가 나가면 Spring 기본 핸들러가 로그만 남기고 상태는
    // 그대로 방치한다 — 여기서 잡아 FAILED로 전이시켜야 폴링이 멈춘 이유를 안다.
    private void handleFailure(String versionName, Exception e) {
        log.error("Job '{}' 처리 중 실패했습니다.", versionName, e);
        try {
            packageJobService.changeStatus(versionName, JobStatus.FAILED);
        } catch (Exception failedTransitionError) {
            // changeStatus 자체가 실패하면(DB 순간 장애 등) 여기서도 못 잡으면 Job이
            // 이전 단계 상태로 영원히 좌초한다 — 로그만 남기고 삼킨다. 재기동 시
            // OrphanJobCleaner가 이 좌초 상태도 정리한다.
            log.error("Job '{}' FAILED 전이에도 실패했습니다.", versionName, failedTransitionError);
        }
    }

    /** Phase 5가 채운다 (FN-08 폴더 확보, FN-09 업로드). */
    private void upload(String versionName) {}
}
