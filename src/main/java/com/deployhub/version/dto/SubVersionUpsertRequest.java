package com.deployhub.version.dto;

import com.deployhub.common.ErrorCode;
import com.deployhub.common.ValidatedRequest;
import com.deployhub.version.entity.SubmitStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 서브버전 등록·수정 항목. {@code imageTags}를 비우면 {@code code:version} 1건을 자동 생성한다. */
public record SubVersionUpsertRequest(
        @NotBlank @Size(max = 50) @Schema(example = "cc") String code,
        @NotBlank @Size(max = 50) @Schema(example = "v2.0.25") String version,
        @Size(max = 10000) @Schema(description = "이 모듈의 변경 사항") String note,
        @NotNull @Schema(description = "문서 표기 순서") Integer sortOrder,
        // 요청 본문이 "내가 아는 현재 상태"의 선언이 된다 — 값이 달라졌는데 UNCHANGED면 E-0208로 거절해
        // 화면이 stale이라는 사실이 조용히 묻히지 않게 한다.
        @NotNull @Schema(description = "확인 상태. 값을 바꾸면서 UNCHANGED는 보낼 수 없다") SubmitStatus submitStatus,
        @Size(max = 30) @Schema(description = "명시하지 않으면 {code}:{version} 1건을 자동 생성한다 (최대 30건)")
        List<@NotBlank @Size(max = 200) String> imageTags)
        implements ValidatedRequest {

    @Override
    public ErrorCode validationErrorCode() {
        return ErrorCode.SUB_VERSION_VALIDATION_FAILED;
    }
}
