package com.deployhub.job.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

/** 업로드 파일 URL 목록. */
@Builder
public record PackageFilesResponse(
        @Schema(description = "메인버전명", example = "2026.08.05") String versionName,
        @Schema(description = "패키지 폴더 조직 범위 공유 링크. 전달의 기본 창구다 (FN-08에서 발급)")
                String folderUrl,
        @Schema(description = "Job 종료 시각") Instant finishedAt,
        @Schema(description = "보존 정책으로 정리된 시각. null이 아니면 아래 URL은 더 이상 유효하지 않다")
                Instant deletedAt,
        @Schema(description = "파일별 정보") List<PackageFileResponse> files) {}
