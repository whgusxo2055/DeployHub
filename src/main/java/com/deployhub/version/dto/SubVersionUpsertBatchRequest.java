package com.deployhub.version.dto;

import com.deployhub.common.ErrorCode;
import com.deployhub.common.ValidatedRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * {@code PUT .../sub-versions} 요청 바디를 감싸는 래퍼. 원소 검증이 필요한
 * {@code @RequestBody List<T>}를 컨트롤러 파라미터에 직접 놓으면 Spring이 이를 body
 * 검증({@link org.springframework.web.bind.MethodArgumentNotValidException})으로 처리할지,
 * 파라미터 검증(Spring 6.1의 {@code HandlerMethodValidationException})으로 처리할지
 * 모호해질 수 있다. 단일 객체로 감싸면 이 모호함 없이 {@code @Valid} cascade가 확실히 동작한다.
 */
public record SubVersionUpsertBatchRequest(
        @NotEmpty @Schema(description = "등록·수정할 서브버전 목록") List<@Valid SubVersionUpsertRequest> items)
        implements ValidatedRequest {

    @Override
    public ErrorCode validationErrorCode() {
        return ErrorCode.SUB_VERSION_VALIDATION_FAILED;
    }
}
