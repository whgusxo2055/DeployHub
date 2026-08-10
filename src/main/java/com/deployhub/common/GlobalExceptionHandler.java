package com.deployhub.common;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    // 예: GET /api/package-jobs?status=foo — JobStatus 같은 enum 쿼리 파라미터가 유효하지
    // 않으면 여기로 온다. 없으면 handleUnexpected가 500 + 스택트레이스로 처리해버린다.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(ErrorCode.INVALID_QUERY_PARAMETER.getHttpStatus())
                .body(ApiErrorResponse.of(
                        ErrorCode.INVALID_QUERY_PARAMETER,
                        ErrorCode.INVALID_QUERY_PARAMETER.getDefaultMessage(),
                        List.of("%s: '%s'".formatted(ex.getName(), ex.getValue()))));
    }

    /**
     * 매핑되지 않은 경로는 정적 리소스 조회로 넘어가고, 거기서도 없으면 이 예외가 난다.
     * 없으면 {@code handleUnexpected}가 500 + 스택트레이스로 처리해 <b>클라이언트의 URL
     * 오타가 서버 장애로 보고된다</b> — 오류 로그도 스택트레이스로 뒤덮인다
     * ({@code MethodArgumentTypeMismatchException}을 따로 잡아 둔 것과 같은 이유).
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException ex) {
        log.warn("매핑되지 않은 경로 요청: {} {}", ex.getHttpMethod(), ex.getResourcePath());
        return ResponseEntity.status(ErrorCode.ENDPOINT_NOT_FOUND.getHttpStatus())
                .body(ApiErrorResponse.of(
                        ErrorCode.ENDPOINT_NOT_FOUND,
                        ErrorCode.ENDPOINT_NOT_FOUND.getDefaultMessage(),
                        List.of()));
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
