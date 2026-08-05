package com.deployhub.job.controller;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.job.dto.PackageItemRetryRequest;
import com.deployhub.job.dto.PackageJobCreateRequest;
import com.deployhub.job.dto.PackageJobDetailResponse;
import com.deployhub.job.dto.PackageJobResponse;
import com.deployhub.job.entity.JobStatus;
import com.deployhub.job.service.JobOrchestrator;
import com.deployhub.job.service.PackageJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FN-03 매니페스트 확정과 Job 조회(구현계획서 Phase 3). 메인버전 경로에 종속된 엔드포인트도
 * 여기 둔다 — 기능 소유권(job 도메인) 기준으로 나눴다. {@link com.deployhub.version.controller.MainVersionController}가
 * job 서비스를 주입받지 않도록 하기 위함이다.
 */
@RestController
@Tag(name = "패키지 Job")
@RequiredArgsConstructor
public class PackageJobController {

    private final PackageJobService packageJobService;
    private final JobOrchestrator jobOrchestrator;

    @Operation(summary = "직전 메인버전 대비 변경된 컴포넌트 조회 (FN-03)")
    @ApiResponse(responseCode = "404", description = "E-0101: 메인버전 없음")
    @GetMapping("/api/main-versions/{versionName}/changed-components")
    public List<String> getChangedComponents(@PathVariable String versionName) {
        return packageJobService.changedComponents(versionName);
    }

    @Operation(summary = "매니페스트 확정 + Job 생성 (FN-03, FN-11 중복방지)")
    @ApiResponse(responseCode = "400", description = "E-0301: 잘못된/중복 image_tag, E-0303: 패키징 대상 없음")
    @ApiResponse(responseCode = "404", description = "E-0101: 메인버전 없음")
    @ApiResponse(responseCode = "409", description = "E-0302: 중복 Job, E-0304: 디스크 부족, E-0305: PENDING 서브버전 존재, E-1301: 동시 요청 충돌")
    @ApiResponse(responseCode = "503", description = "E-1502: 실행 대기열 포화")
    @PostMapping("/api/main-versions/{versionName}/package-job")
    public ResponseEntity<PackageJobDetailResponse> createPackageJob(
            @PathVariable String versionName, @Valid @RequestBody PackageJobCreateRequest request) {
        PackageJobDetailResponse created = packageJobService.create(versionName, request);
        // 생성 트랜잭션이 커밋된 뒤에 워커를 제출한다 — 서비스 메서드 안에서 제출하면
        // 워커가 커밋 전에 시작해 아직 안 보이는 Job 행을 조회하게 된다.
        try {
            jobOrchestrator.start(versionName);
        } catch (TaskRejectedException e) {
            // 큐(queueCapacity=100)까지 가득 찬 경우 — Job 행은 이미 커밋됐으므로 여기서
            // 바로 FAILED로 돌려 PENDING 좀비로 남지 않게 한다(재기동 전에도 즉시 재시도 가능).
            packageJobService.changeStatus(versionName, JobStatus.FAILED);
            throw new ApiException(ErrorCode.JOB_QUEUE_SATURATED);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "패키지 Job 상태·진행률 조회 (폴링 대상)")
    @ApiResponse(responseCode = "404", description = "E-0306: 패키지 Job 없음")
    @GetMapping("/api/package-jobs/{versionName}")
    public PackageJobDetailResponse getJob(@PathVariable String versionName) {
        return packageJobService.getDetail(versionName);
    }

    @Operation(summary = "실패 항목 수동 재시도 (FN-07)")
    @ApiResponse(responseCode = "400", description = "E-0303: 재시도 대상 없음")
    @ApiResponse(responseCode = "404", description = "E-0306: 패키지 Job 없음")
    @ApiResponse(responseCode = "409", description = "E-0702: 재시도 불가 상태, E-0703: 작업 디렉터리 소실")
    @ApiResponse(responseCode = "503", description = "E-1502: 실행 대기열 포화")
    @PostMapping("/api/package-jobs/{versionName}/retry")
    public PackageJobDetailResponse retryPackageJob(
            @PathVariable String versionName, @Valid @RequestBody PackageItemRetryRequest request) {
        PackageJobDetailResponse updated = packageJobService.retry(versionName, request);
        try {
            jobOrchestrator.resume(versionName);
        } catch (TaskRejectedException e) {
            packageJobService.changeStatus(versionName, JobStatus.FAILED);
            throw new ApiException(ErrorCode.JOB_QUEUE_SATURATED);
        }
        return updated;
    }

    @Operation(summary = "패키지 Job 목록 조회 (FN-11)")
    @GetMapping("/api/package-jobs")
    public List<PackageJobResponse> listJobs(
            @Parameter(description = "상태 필터") @RequestParam(required = false) JobStatus status) {
        return packageJobService.list(status);
    }
}
