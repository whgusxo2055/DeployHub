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
        // 구분자는 '-' 하나만, 선행 0과 '-0'은 금지한다 — 정렬키가 구분자를 통일하고 0으로 채우므로
        // '2026.08.05.1'·'2026.08.05-01'·'2026.08.05-0' 같은 별칭이 PK는 다른데 sort_key만 같아진다.
        // sort_key에는 UNIQUE 인덱스(V3)가 있어 그대로 두면 existsById 검사를 지나 500으로 터진다.
        @Pattern(
                regexp = "^\\d{4}\\.\\d{2}\\.\\d{2}(-[1-9]\\d{0,2})?$",
                message = "날짜-index 형식이어야 합니다 (예: 2026.08.05, 2026.08.05-1)")
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
