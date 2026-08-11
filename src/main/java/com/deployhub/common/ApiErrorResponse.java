package com.deployhub.common;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

/** 공통 오류 응답 스키마. */
@Schema(description = "공통 오류 응답")
@Builder
public record ApiErrorResponse(
        @Schema(description = "오류 코드", example = "E-0203") String code,
        @Schema(description = "사용자에게 보여줄 메시지") String message,
        @Schema(description = "상세 원인 목록") List<String> details,
        @Schema(description = "발생 시각") Instant timestamp) {

    public static ApiErrorResponse of(ErrorCode errorCode, String message, List<String> details) {
        return ApiErrorResponse.builder()
                .code(errorCode.getCode())
                .message(message)
                .details(details)
                .timestamp(Instant.now())
                .build();
    }
}
