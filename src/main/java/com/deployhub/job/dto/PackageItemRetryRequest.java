package com.deployhub.job.dto;

import com.deployhub.common.ErrorCode;
import com.deployhub.common.ValidatedRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 수동 재시도 요청. {@code imageTags}를 비우면 FAILED 항목 전체가 대상이다. */
public record PackageItemRetryRequest(
        @Size(max = 500) @Schema(description = "재시도 대상. 비우면 FAILED 전체")
        List<@NotBlank @Size(max = 200) String> imageTags)
        implements ValidatedRequest {

    @Override
    public ErrorCode validationErrorCode() {
        return ErrorCode.INVALID_IMAGE_TAG_SELECTION;
    }
}
