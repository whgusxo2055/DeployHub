package com.deployhub.job.dto;

import com.deployhub.common.ErrorCode;
import com.deployhub.common.ValidatedRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 매니페스트 확정 요청. {@code imageTags}를 비우면 변경된 컴포넌트 전체가 기본값이다. */
public record PackageJobCreateRequest(
        @Size(max = 500) @Schema(description = "패키징 대상. 비우면 변경된 컴포넌트 전체가 기본값")
        List<@NotBlank @Size(max = 200) String> imageTags,
        @NotBlank @Size(max = 100) @Schema(description = "실행자") String createdBy,
        @Schema(
                        description =
                                "true면 완료된(DONE) Job을 초기화해 재생성하고, 디스크 여유 공간 부족 경고도 함께 우회한다. "
                                        + "진행 중인 Job은 force로도 뚫지 않는다")
                boolean force)
        implements ValidatedRequest {

    @Override
    public ErrorCode validationErrorCode() {
        return ErrorCode.INVALID_IMAGE_TAG_SELECTION;
    }
}
