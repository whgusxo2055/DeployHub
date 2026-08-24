package com.deployhub.job.dto;

import com.deployhub.common.ErrorCode;
import com.deployhub.common.ValidatedRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 매니페스트 확정 요청. {@code imageTags}가 최종 기준이다 — 선택 범위는 메인버전의 전체
 * 컴포넌트라 변경분·미변경분을 가리지 않는다.
 */
public record PackageJobCreateRequest(
        @NotEmpty @Size(max = 500) @Schema(description = "패키징 대상 image_tag 목록 (필수). 메인버전에 등록된 컴포넌트여야 한다")
        List<@NotBlank @Size(max = 200) String> imageTags,
        @Schema(description = "true면 완료된(DONE) Job을 초기화해 재생성한다. 진행 중인 Job은 force로도 뚫지 않는다")
        boolean force)
        implements ValidatedRequest {

    @Override
    public ErrorCode validationErrorCode() {
        return ErrorCode.INVALID_IMAGE_TAG_SELECTION;
    }
}
