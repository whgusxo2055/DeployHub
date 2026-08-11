package com.deployhub.job.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/** 정리 결과. 배치와 수동 정리가 같은 형태를 쓴다 — 수동 정리는 대상이 1건인 배치와 다르지 않다. */
@Builder
public record PackageCleanupResponse(
        @Schema(description = "true면 대상만 산출하고 실제 삭제는 하지 않는다") boolean dryRun,
        @Schema(description = "작업 디렉터리를 정리한(또는 정리할) 메인버전 목록") List<String> localCleaned,
        @Schema(
                        description =
                                "SharePoint 폴더를 실제로 지운(dryRun이면 지울) 메인버전 목록. 폴더가 없던 Job은 2단계를 처리해도 여기 오르지 않는다 — 처리 여부는 deleted_at으로 확인한다")
                List<String> sharePointCleaned,
        @Schema(description = "정리에 실패해 다음 배치로 미룬 메인버전 목록 (E-1402/E-1403)")
                List<String> failed) {}
