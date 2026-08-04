package com.deployhub.common;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 4.3절 오류 응답 스키마를 모든 컨트롤러에 일괄 적용한다. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiErrorResponse.of(errorCode, ex.getMessage(), ex.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> "%s: %s".formatted(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();

        // 검증에 실패한 요청 DTO가 자기 도메인 코드를 알려준다 (E-0000 같은 공용 코드를 쓰지 않는다).
        ErrorCode errorCode = ex.getBindingResult().getTarget() instanceof ValidatedRequest validatedRequest
                ? validatedRequest.validationErrorCode()
                : logUnmappedValidationTargetAndFallBack(ex);

        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiErrorResponse.of(errorCode, errorCode.getDefaultMessage(), details));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        log.error("예기치 못한 오류가 발생했습니다.", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(ApiErrorResponse.of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage(), List.of()));
    }

    private ErrorCode logUnmappedValidationTargetAndFallBack(MethodArgumentNotValidException ex) {
        Object target = ex.getBindingResult().getTarget();
        log.error(
                "{}가 ValidatedRequest를 구현하지 않아 검증 실패 코드를 도메인별로 내려줄 수 없습니다. "
                        + "이 DTO에 ValidatedRequest를 구현하세요.",
                target == null ? "요청 대상" : target.getClass().getName());
        return ErrorCode.INTERNAL_ERROR;
    }
}
