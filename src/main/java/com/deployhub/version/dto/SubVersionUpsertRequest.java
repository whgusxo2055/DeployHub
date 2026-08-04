package com.deployhub.version.dto;

import com.deployhub.common.ErrorCode;
import com.deployhub.common.ValidatedRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 서브버전 등록·수정 항목. {@code imageTags}를 비워두면 {@code code:version} 1건을
 * 자동 생성한다 (구현계획서 Phase 1-2 컴포넌트 생성 규칙).
 */
public record SubVersionUpsertRequest(
        @NotBlank @Size(max = 50) @Schema(example = "cc") String code,
        @NotBlank @Size(max = 50) @Schema(example = "v2.0.25") String version,
        @Schema(description = "이 모듈의 변경 사항") String note,
        @NotNull @Schema(description = "문서 표기 순서") Integer sortOrder,
        @Schema(description = "명시하지 않으면 {code}:{version} 1건을 자동 생성한다")
        List<@NotBlank @Size(max = 200) String> imageTags)
        implements ValidatedRequest {

    @Override
    public ErrorCode validationErrorCode() {
        return ErrorCode.SUB_VERSION_VALIDATION_FAILED;
    }
}
