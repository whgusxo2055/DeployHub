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
        // index를 3자리로 (000 제외) 고정한다 — 이 이름 자체가 정렬키다. 자리수가 섞이면
        // 문자열 비교가 뒤집히고('2026.08.24.010' < '2026.08.24.2'), 000은 001과 의미가 겹친다.
        @Pattern(
                regexp = "^\\d{4}\\.\\d{2}\\.\\d{2}\\.(00[1-9]|0[1-9]\\d|[1-9]\\d{2})$",
                message = "배포일자.index(3자리) 형식이어야 합니다 (예: 2026.08.24.001)")
        @Schema(description = "메인버전명 (배포일자.index). 그날 첫 릴리즈가 001", example = "2026.08.24.001")
        String versionName,
        @Size(max = 20000) @Schema(description = "고객사 전달용 릴리즈 노트") String releaseNote,
        @Size(max = 20000) @Schema(description = "이번 배포의 DB 적용 안내") String sqlScript)
        implements ValidatedRequest {

    @Override
    public ErrorCode validationErrorCode() {
        return ErrorCode.MAIN_VERSION_VALIDATION_FAILED;
    }
}
