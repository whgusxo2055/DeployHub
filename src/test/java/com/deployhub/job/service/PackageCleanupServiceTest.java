package com.deployhub.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.job.dto.PackageCleanupResponse;
import com.deployhub.job.entity.JobStatus;
import com.deployhub.job.entity.PackageJob;
import com.deployhub.job.repository.PackageJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 배치가 "건너뜀"과 "실패"를 가르는 규칙. HTTP로는 재실행 경합을 재현할 수 없어 단위로 고정한다. */
@ExtendWith(MockitoExtension.class)
class PackageCleanupServiceTest {

    @Mock
    private PackageJobRepository packageJobRepository;

    @Mock
    private PackagePurgeService packagePurgeService;

    @Test
    void 그_사이_재실행된_Job은_실패가_아니라_건너뛴다() {
        PackageJob job = PackageJob.builder()
                .versionName("2027.01.01")
                .status(JobStatus.DONE)
                .finishedAt(Instant.now().minus(Duration.ofDays(400)))
                .build();
        when(packageJobRepository.findByStatusInOrderByFinishedAtDesc(any())).thenReturn(List.of(job));
        when(packagePurgeService.purgeLocal(anyString(), anyString(), any()))
                .thenThrow(new ApiException(ErrorCode.PACKAGE_CLEANUP_RERUN, List.of("status=PENDING")));

        PackageCleanupResponse response =
                new PackageCleanupService(packageJobRepository, packagePurgeService, 24, 90, 10)
                        .cleanup(false, "test");

        // E-1405는 "이번엔 건너뛴다"이지 실패가 아니다 — cleanup의 javadoc이 약속한 동작이다.
        assertThat(response.failed()).isEmpty();
        assertThat(response.localCleaned()).isEmpty();
    }
}
