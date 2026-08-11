package com.deployhub.common;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Builder;

/** 목록 조회 응답의 공통 페이지 포맷. */
@Schema(description = "페이지 응답")
@Builder
public record PageResponse<T>(
        @Schema(description = "현재 페이지 항목") List<T> items,
        @Schema(description = "전체 건수") long totalCount,
        @Schema(description = "현재 페이지 (0-base)") int page,
        @Schema(description = "페이지 크기") int size) {}
