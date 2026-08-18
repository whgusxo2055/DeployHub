package com.deployhub.version.dto;

import com.deployhub.common.ErrorCode;
import com.deployhub.common.ValidatedRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record MainVersionCreateRequest(
        @NotBlank
        @Size(max = 20)
        // index를 3자리로 제한한다 — 정렬키가 %03d로 채우므로 그 이상은 순서가 다시 뒤집힌다.
        @Pattern(
                regexp = "^\\d{4}\\.\\d{2}\\.\\d{2}([.-]\\d{1,3})?$",
                message = "날짜.index 형식이어야 합니다 (예: 2026.08.05, 2026.08.05-1)")
        @Schema(description = "메인버전명 (배포일자.index)", example = "2026.08.05")
        String versionName,
        @Size(max = 20000) @Schema(description = "고객사 전달용 릴리즈 노트") String releaseNote,
        @Size(max = 20000) @Schema(description = "이번 배포의 DB 적용 안내") String sqlScript)
        implements ValidatedRequest {

    @Override
    public ErrorCode validationErrorCode() {
        return ErrorCode.MAIN_VERSION_VALIDATION_FAILED;
    }
}
