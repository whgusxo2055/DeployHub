package com.deployhub.version.controller;

import com.deployhub.common.PageResponse;
import com.deployhub.version.dto.MainVersionCreateRequest;
import com.deployhub.version.dto.MainVersionDetailResponse;
import com.deployhub.version.dto.MainVersionInfoResponse;
import com.deployhub.version.dto.MainVersionSummaryResponse;
import com.deployhub.version.dto.MainVersionUpdateRequest;
import com.deployhub.version.dto.PackagingEligibilityResponse;
import com.deployhub.version.dto.SubVersionSavedResponse;
import com.deployhub.version.dto.SubVersionUpsertBatchRequest;
import com.deployhub.version.service.MainVersionService;
import com.deployhub.version.service.SubVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 메인버전 CRUD와 메인버전 경로에 종속된 서브버전 일괄 등록. 서브버전을 자기 id로 다루는 작업은
 * {@link SubVersionController}가 맡는다 — URL 경로의 종속 여부로 나눴다.
 */
@RestController
@Tag(name = "버전 및 매니페스트 관리")
@RequiredArgsConstructor
public class MainVersionController {

    private final MainVersionService mainVersionService;
    private final SubVersionService subVersionService;

    @Operation(summary = "메인버전 등록")
    @ApiResponse(responseCode = "400", description = "E-0103: 요청 값 검증 실패")
    @ApiResponse(responseCode = "409", description = "E-0102: 이미 등록된 메인버전")
    @PostMapping("/api/main-versions")
    public ResponseEntity<MainVersionInfoResponse> create(@Valid @RequestBody MainVersionCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mainVersionService.create(request));
    }

    @Operation(summary = "메인버전 수정")
    @ApiResponse(responseCode = "400", description = "E-0103: 요청 값 검증 실패")
    @ApiResponse(responseCode = "404", description = "E-0101: 메인버전 없음")
    @PutMapping("/api/main-versions/{versionName}")
    public MainVersionInfoResponse update(
            @PathVariable String versionName, @Valid @RequestBody MainVersionUpdateRequest request) {
        return mainVersionService.update(versionName, request);
    }

    @Operation(summary = "서브버전 일괄 등록·수정 (컴포넌트 자동 생성)")
    @ApiResponse(
            responseCode = "400",
            description = "E-0205: 요청 값 검증 실패, E-0203: 메인버전 내 image_tag 중복,"
                    + " E-0206: 레지스트리에 없는 image_tag")
    @ApiResponse(responseCode = "404", description = "E-0101: 메인버전 없음")
    @ApiResponse(
            responseCode = "409",
            description = "E-0204: Job이 있는 메인버전은 수정 불가, E-0207: 동시 요청 충돌")
    @PutMapping("/api/main-versions/{versionName}/sub-versions")
    public List<SubVersionSavedResponse> upsertSubVersions(
            @PathVariable String versionName, @Valid @RequestBody SubVersionUpsertBatchRequest request) {
        return subVersionService.upsertAll(versionName, request.items());
    }

    @Operation(summary = "메인버전 목록 조회 (FN-01)")
    @GetMapping("/api/main-versions")
    public PageResponse<MainVersionSummaryResponse> list(
            @Parameter(description = "메인버전명 부분 검색") @RequestParam(required = false) String keyword,
            Pageable pageable) {
        return mainVersionService.list(keyword, pageable);
    }

    @Operation(summary = "메인버전 기준 서브버전 조회 (FN-02)")
    @ApiResponse(responseCode = "404", description = "E-0101: 메인버전 없음")
    @GetMapping("/api/main-versions/{versionName}")
    public MainVersionDetailResponse getDetail(@PathVariable String versionName) {
        return mainVersionService.getDetail(versionName);
    }

    @Operation(summary = "메인버전 패키징 가능 여부 확인 (FN-02-1)")
    @ApiResponse(responseCode = "404", description = "E-0101: 메인버전 없음")
    @GetMapping("/api/main-versions/{versionName}/packaging-eligibility")
    public PackagingEligibilityResponse getPackagingEligibility(@PathVariable String versionName) {
        return mainVersionService.getPackagingEligibility(versionName);
    }
}
