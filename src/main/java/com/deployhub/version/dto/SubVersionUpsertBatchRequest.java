package com.deployhub.version.dto;

import com.deployhub.common.ErrorCode;
import com.deployhub.common.ValidatedRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 요청 바디 래퍼. {@code @RequestBody List<T>}를 컨트롤러 파라미터에 직접 놓으면 Spring이
 * body 검증과 파라미터 검증 중 무엇으로 처리할지 모호해진다 — 단일 객체로 감싸면 {@code @Valid} cascade가 확실해진다.
 */
public record SubVersionUpsertBatchRequest(
        // 상한이 없으면 요청 하나가 레지스트리 조회 N건 + 트랜잭션을 통째로 붙잡는다(무인증 API).
        @NotEmpty @Size(max = 50) @Schema(description = "등록·수정할 서브버전 목록 (최대 50건)")
                List<@Valid SubVersionUpsertRequest> items)
        implements ValidatedRequest {

    @Override
    public ErrorCode validationErrorCode() {
        return ErrorCode.SUB_VERSION_VALIDATION_FAILED;
    }
}
