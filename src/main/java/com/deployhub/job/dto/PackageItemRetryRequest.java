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
        List<@NotBlank @Size(max = 200) String> imageTags,
        @Schema(description = "true면 작업 디렉터리 소실(E-0703) 시에도 전체 재수집으로 진행") boolean force)
        implements ValidatedRequest {

    @Override
    public ErrorCode validationErrorCode() {
        return ErrorCode.INVALID_IMAGE_TAG_SELECTION;
    }
}
