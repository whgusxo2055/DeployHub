package com.deployhub.job.dto;

import com.deployhub.common.ErrorCode;
import com.deployhub.common.ValidatedRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 매니페스트 확정 요청. {@code imageTags}를 비우면 변경된 컴포넌트 전체가 기본값이고,
 * 명시하면 그 목록이 기본값을 대체한다 — 이때 선택 범위는 메인버전의 전체 컴포넌트라
 * 미변경분만 골라 담는 부분 패키징도 된다.
 */
public record PackageJobCreateRequest(
        @Size(max = 500) @Schema(description = "패키징 대상. 비우면 변경된 컴포넌트 전체가 기본값 (미변경분도 지정 가능)")
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
