package com.deployhub.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.util.StringUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 공통 오류 응답 스키마를 모든 컨트롤러에 일괄 적용한다. */
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
        // getAllErrors: 필드 제약뿐 아니라 클래스 레벨 제약(@AssertTrue 등)도 details에 담는다.
        List<String> details = ex.getBindingResult().getAllErrors().stream()
                .map(error -> error instanceof FieldError fieldError
                        ? "%s: %s".formatted(fieldError.getField(), fieldError.getDefaultMessage())
                        : "%s: %s".formatted(error.getObjectName(), error.getDefaultMessage()))
                .toList();

        // 검증에 실패한 DTO가 자기 도메인 코드를 알려준다 — 공용 "검증 실패" 코드를 쓰지 않는다.
        ErrorCode errorCode = ex.getBindingResult().getTarget() instanceof ValidatedRequest validatedRequest
                ? validatedRequest.validationErrorCode()
                : logUnmappedValidationTargetAndFallBack(ex);

        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiErrorResponse.of(errorCode, errorCode.getDefaultMessage(), details));
    }

    // enum 쿼리 파라미터가 유효하지 않으면 여기로 온다 — 없으면 클라이언트 실수가 500이 된다.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(ErrorCode.INVALID_QUERY_PARAMETER.getHttpStatus())
                .body(ApiErrorResponse.of(
                        ErrorCode.INVALID_QUERY_PARAMETER,
                        ErrorCode.INVALID_QUERY_PARAMETER.getDefaultMessage(),
                        List.of("%s: '%.100s'".formatted(ex.getName(), String.valueOf(ex.getValue())))));
    }

    /**
     * 매핑되지 않은 경로. 이게 없으면 클라이언트의 URL 오타가 500 + 스택트레이스로 보고된다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        // getResourcePath()가 아니라 원본 URI를 찍는다 — 전자는 선행·중복·후행 슬래시를 정규화해 버려서
        // `//api/x`·`/api/x/`처럼 클라이언트가 잘못 부른 경로가 매핑된 경로와 똑같이 보인다.
        log.warn("매핑되지 않은 경로 요청: {} {}", ex.getHttpMethod(), request.getRequestURI());
        return ResponseEntity.status(ErrorCode.ENDPOINT_NOT_FOUND.getHttpStatus())
                .body(ApiErrorResponse.of(
                        ErrorCode.ENDPOINT_NOT_FOUND,
                        ErrorCode.ENDPOINT_NOT_FOUND.getDefaultMessage(),
                        List.of()));
    }

    /**
     * 아래 네 개는 Spring MVC가 400/405/415로 번역하는 표준 예외다. {@code ExceptionHandlerExceptionResolver}가
     * {@code DefaultHandlerExceptionResolver}보다 먼저 돌기 때문에, 이 핸들러가 없으면
     * {@link #handleUnexpected}가 먼저 잡아 클라이언트 실수가 500 + 스택트레이스로 보고된다
     * (무인증 API라 깨진 JSON 반복 POST만으로 에러 로그를 오염시킬 수 있다).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        // 메시지에 파싱 위치·원문 조각이 들어 있어 응답에 싣지 않는다.
        log.warn("요청 본문을 읽을 수 없습니다: {}", ex.getMessage());
        return errorResponse(ErrorCode.MALFORMED_REQUEST);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(ErrorCode.MALFORMED_REQUEST.getHttpStatus())
                .body(ApiErrorResponse.of(
                        ErrorCode.MALFORMED_REQUEST,
                        ErrorCode.MALFORMED_REQUEST.getDefaultMessage(),
                        List.of("%s: 필수 파라미터가 없습니다.".formatted(ex.getParameterName()))));
    }

    /** {@code Allow} 헤더는 표준 처리기가 붙이던 것이라 여기서 그대로 유지한다. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus());
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            builder.header(HttpHeaders.ALLOW, StringUtils.collectionToDelimitedString(ex.getSupportedHttpMethods(), ", "));
        }
        return builder.body(ApiErrorResponse.of(
                ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getDefaultMessage(), List.of()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return errorResponse(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        log.error("예기치 못한 오류가 발생했습니다.", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(ApiErrorResponse.of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage(), List.of()));
    }

    private static ResponseEntity<ApiErrorResponse> errorResponse(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiErrorResponse.of(errorCode, errorCode.getDefaultMessage(), List.of()));
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
