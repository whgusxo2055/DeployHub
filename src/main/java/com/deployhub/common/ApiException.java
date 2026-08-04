package com.deployhub.common;

import java.util.List;
import lombok.Getter;

/** 오류 코드 체계에 맞춰 응답하기 위한 공통 예외. */
@Getter
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<String> details;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage(), List.of());
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public ApiException(ErrorCode errorCode, String message, List<String> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }
}
