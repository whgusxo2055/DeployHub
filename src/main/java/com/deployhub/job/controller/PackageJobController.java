package com.deployhub.job.controller;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.job.dto.PackageCleanupResponse;
import com.deployhub.job.dto.PackageFilesResponse;
import com.deployhub.job.dto.PackageItemRetryRequest;
import com.deployhub.job.dto.PackageJobCreateRequest;
import com.deployhub.job.dto.PackageJobDetailResponse;
import com.deployhub.job.dto.PackageJobResponse;
import com.deployhub.job.entity.JobStatus;
import com.deployhub.job.service.JobOrchestrator;
import com.deployhub.job.service.PackageCleanupService;
import com.deployhub.job.service.PackageFileService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 매니페스트 확정과 Job 조회. 메인버전 경로에 종속된 엔드포인트도 기능 소유권 기준으로 여기 둔다 —
 * {@link com.deployhub.version.controller.MainVersionController}가 job 서비스를 주입받지 않게 하기 위해서다.
 */
@RestController
@Tag(name = "패키지 Job")
@RequiredArgsConstructor
public class PackageJobController {

    private final PackageJobService packageJobService;
    private final JobOrchestrator jobOrchestrator;
    private final PackageFileService packageFileService;
    private final PackageCleanupService packageCleanupService;

    @Operation(summary = "패키징 기본 선택값 조회 — 직전 메인버전 대비 변경된 컴포넌트 (FN-03)")
    @ApiResponse(responseCode = "404", description = "E-0101: 메인버전 없음")
    @GetMapping("/api/main-versions/{versionName}/changed-components")
    public List<String> getChangedComponents(@PathVariable String versionName) {
        return packageJobService.changedComponents(versionName);
    }

    @Operation(summary = "매니페스트 확정 + Job 생성 (FN-03, FN-11 중복방지)")
    @ApiResponse(responseCode = "400", description = "E-0301: 잘못된/중복 image_tag, E-0303: 패키징 대상 없음")
    @ApiResponse(responseCode = "404", description = "E-0101: 메인버전 없음")
    @ApiResponse(responseCode = "409", description = "E-0302: 중복 Job, E-0304: 디스크 부족" +
            ", E-0305: PENDING 담당 영역 존재 또는 서브버전 0건, E-1301: 동시 요청 충돌")
    @ApiResponse(responseCode = "503", description = "E-1502: 실행 대기열 포화")
    @PostMapping("/api/main-versions/{versionName}/package-job")
    public ResponseEntity<PackageJobDetailResponse> createPackageJob(
            @PathVariable String versionName, @Valid @RequestBody PackageJobCreateRequest request) {
        PackageJobDetailResponse created = packageJobService.create(versionName, request);
        // 커밋 후에 워커를 제출한다 — 서비스 안에서 제출하면 워커가 아직 안 보이는 Job 행을 조회한다.
        try {
            jobOrchestrator.start(versionName);
        } catch (TaskRejectedException e) {
            // 큐까지 가득 찬 경우 — Job 행은 이미 커밋됐으므로 바로 FAILED로 돌려 PENDING 좀비를 막는다.
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
    @ApiResponse(responseCode = "400", description = "E-0301: 요청 값 검증 실패, E-0303: 재시도 대상 없음")
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

    @Operation(
            summary = "업로드 파일 URL 목록 조회 (FN-10)",
            description = "폴더 조직 범위 공유 링크(기본 전달 창구)와 파일별 URL을 함께 제공한다.")
    @ApiResponse(responseCode = "404", description = "E-0306: 패키지 Job 없음")
    @ApiResponse(responseCode = "409", description = "E-1201: Job 미완료 — details에 현재 상태·진행률")
    @GetMapping("/api/package-jobs/{versionName}/files")
    public PackageFilesResponse getFiles(@PathVariable String versionName) {
        return packageFileService.listFiles(versionName);
    }

    @Operation(summary = "패키지 수동 정리 (FN-11)", description = "SharePoint 폴더와 로컬 작업 디렉터리를 즉시 삭제한다. Job 이력 행은 남는다.")
    @ApiResponse(responseCode = "403", description = "E-0452: SharePoint 폴더 삭제 권한 부족")
    @ApiResponse(responseCode = "404", description = "E-0306: 패키지 Job 없음")
    @ApiResponse(responseCode = "409", description = "E-1404: 진행 중인 Job")
    @ApiResponse(responseCode = "502", description = "E-0451: Graph 토큰 발급 실패")
    @ApiResponse(responseCode = "503", description = "E-0453: Graph 일시적 응답 불가")
    @DeleteMapping("/api/package-jobs/{versionName}/package")
    public PackageCleanupResponse deletePackage(@PathVariable String versionName) {
        return packageCleanupService.cleanupOne(versionName);
    }

    /**
     * {@code dryRun} 기본값이 {@code true}다 — 무인증 API라 파라미터 없는 POST 한 방이
     * (Swagger UI의 "Try it out" 기본 상태가) 실삭제가 되면 안 된다.
     */
    @Operation(
            summary = "보존·정리 배치 수동 실행 (FN-11)",
            description = "기본값은 대상만 산출하는 dry run이다. 실제 삭제는 dryRun=false를 명시해야 한다.")
    @PostMapping("/api/admin/cleanup")
    public PackageCleanupResponse runCleanup(
            @Parameter(description = "false를 명시해야 실제로 삭제한다") @RequestParam(defaultValue = "true")
                    boolean dryRun) {
        return packageCleanupService.cleanup(dryRun, "api");
    }
}
