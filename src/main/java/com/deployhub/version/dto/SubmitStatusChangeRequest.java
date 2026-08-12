package com.deployhub.version.dto;

import com.deployhub.common.ErrorCode;
import com.deployhub.common.ValidatedRequest;
import com.deployhub.version.entity.SubmitStatus;
import jakarta.validation.constraints.NotNull;

public record SubmitStatusChangeRequest(@NotNull SubmitStatus status) implements ValidatedRequest {

    @Override
    public ErrorCode validationErrorCode() {
        return ErrorCode.SUB_VERSION_VALIDATION_FAILED;
    }
}
