package com.deployhub.version.dto;

import com.deployhub.common.ErrorCode;
import com.deployhub.common.ValidatedRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record MainVersionUpdateRequest(
        @Size(max = 20000) @Schema(description = "고객사 전달용 릴리즈 노트") String releaseNote,
        @Size(max = 20000) @Schema(description = "이번 배포의 DB 적용 안내") String sqlScript)
        implements ValidatedRequest {

    @Override
    public ErrorCode validationErrorCode() {
        return ErrorCode.MAIN_VERSION_VALIDATION_FAILED;
    }
}
