package com.deployhub.job.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.deployhub.job.entity.JobStatus;
import com.deployhub.sharepoint.GraphFolderService;
import com.deployhub.sharepoint.GraphUploadService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 마지막 전이가 {@code changeStatus(DONE)} 고정이 아니라 {@link PackageJobService#finish}를 거치는지
 * 고정한다 — 여기로 오지 않으면 부분 재시도로 건너뛴 FAILED 항목을 남긴 채 Job이 DONE으로 끝나고,
 * 그 항목은 재시도도 못 하는 사이 정리 배치가 tar를 지운다.
 */
@ExtendWith(MockitoExtension.class)
class JobOrchestratorTest {

    private static final String VERSION_NAME = "2026.08.18";

    @Mock
    private PackageJobService packageJobService;

    @Mock
    private PackageValidationService packageValidationService;

    @Mock
    private PackageDownloadService packageDownloadService;

    @Mock
    private GraphFolderService graphFolderService;

    @Mock
    private GraphUploadService graphUploadService;

    @InjectMocks
    private JobOrchestrator orchestrator;

    @Test
    void 마지막_전이는_DONE_고정이_아니라_finish를_거친다() {
        when(packageValidationService.validate(VERSION_NAME)).thenReturn(Map.of());
        when(graphFolderService.ensureFolder(VERSION_NAME)).thenReturn("folder-1");

        orchestrator.start(VERSION_NAME);

        verify(packageJobService).finish(VERSION_NAME);
        verify(packageJobService, never()).changeStatus(VERSION_NAME, JobStatus.DONE);
        // 실패로 새어 나가지 않았는지도 같이 본다 — handleFailure가 삼키면 위 단언이 헛돌 수 있다.
        verify(packageJobService, never()).changeStatus(VERSION_NAME, JobStatus.FAILED);
        verify(graphUploadService).uploadAll(VERSION_NAME, "folder-1");
        verify(packageDownloadService).download(any(), any());
    }
}
