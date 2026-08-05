package com.deployhub.job.service;

import com.deployhub.job.entity.JobStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Job 단계 순차 실행 (구현계획서 Phase 3-2). {@code @Transactional}을 붙이지 않는다 —
 * 긴 트랜잭션을 피하고, 폴링 클라이언트가 단계 중간 상태를 볼 수 있어야 한다. 각 단계는
 * Phase 4~6이 채우는 빈 구현이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobOrchestrator {

    private final PackageJobService packageJobService;

    @Async("jobExecutor")
    public void start(String versionName) {
        try {
            packageJobService.changeStatus(versionName, JobStatus.VALIDATING);
            validate(versionName);

            packageJobService.changeStatus(versionName, JobStatus.DOWNLOADING);
            download(versionName);

            packageJobService.changeStatus(versionName, JobStatus.UPLOADING);
            upload(versionName);

            packageJobService.changeStatus(versionName, JobStatus.DONE);
        } catch (Exception e) {
            // @Async void 메서드 밖으로 예외가 나가면 Spring 기본 핸들러가 로그만 남기고
            // 상태는 그대로 방치한다 — 여기서 잡아 FAILED로 전이시켜야 폴링이 멈춘 이유를 안다.
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
    }

    /** Phase 4가 채운다 (FN-05 산출물 존재 여부 확인). */
    private void validate(String versionName) {}

    /** Phase 4가 채운다 (FN-06-1 다운로드, FN-07 재시도). */
    private void download(String versionName) {}

    /** Phase 5가 채운다 (FN-08 폴더 확보, FN-09 업로드). */
    private void upload(String versionName) {}
}
