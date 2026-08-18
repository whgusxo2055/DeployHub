package com.deployhub.job.service;

import com.deployhub.job.entity.JobStatus;
import com.deployhub.registry.NcrRegistryClient.ManifestInfo;
import com.deployhub.sharepoint.GraphFolderService;
import com.deployhub.sharepoint.GraphUploadService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Job 단계 순차 실행. {@code @Transactional}을 붙이지 않는다 — 긴 트랜잭션을 피하고
 * 폴링 클라이언트가 단계 중간 상태를 볼 수 있어야 한다.
 * {@link #start}는 VALIDATING부터, {@link #resume}(수동 재시도)은 DOWNLOADING부터 재개한다 —
 * 이미 받은 항목을 다시 받지 않기 위해서다. 대신 매니페스트 컨텍스트가 없어 항목별로 즉석 조회한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobOrchestrator {

    private final PackageJobService packageJobService;
    private final PackageValidationService packageValidationService;
    private final PackageDownloadService packageDownloadService;
    private final GraphFolderService graphFolderService;
    private final GraphUploadService graphUploadService;

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

        // DONE 고정이 아니다 — 부분 재시도로 건너뛴 FAILED 항목이 남아 있으면 FAILED로 끝난다.
        packageJobService.finish(versionName);
    }

    // @Async void 밖으로 예외가 나가면 Spring이 로그만 남기고 상태를 방치한다 —
    // 여기서 잡아 FAILED로 전이시켜야 폴링이 멈춘 이유를 안다.
    private void handleFailure(String versionName, Exception e) {
        log.error("Job '{}' 처리 중 실패했습니다.", versionName, e);
        try {
            packageJobService.changeStatus(versionName, JobStatus.FAILED);
        } catch (Exception failedTransitionError) {
            // 전이 자체가 실패해도 삼킨다 — 여기서 던지면 Job이 이전 단계로 영원히 좌초한다.
            // 재기동 시 OrphanJobCleaner가 정리한다.
            log.error("Job '{}' FAILED 전이에도 실패했습니다.", versionName, failedTransitionError);
        }
    }

    /** 폴더 확보 + 업로드. */
    private void upload(String versionName) {
        String folderItemId = graphFolderService.ensureFolder(versionName);
        graphUploadService.uploadAll(versionName, folderItemId);
    }
}
