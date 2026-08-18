package com.deployhub.common;

import java.util.List;
import lombok.Getter;

/**
 * 오류 코드 체계에 맞춰 응답하기 위한 공통 예외.
 *
 * <p><b>메시지는 {@link ErrorCode}에만 있다</b> — 호출부가 자유 문자열을 넘기는 생성자를 두지 않는다.
 * 같은 코드가 지점마다 다른 문구로 나가면 클라이언트가 문구로 분기하게 되고, 문구를 고칠 때
 * 어디를 봐야 하는지도 알 수 없다. 상황별 컨텍스트는 문장이 아니라 {@code details}에 담는다
 * (키=값 형태 권장). 문구 자체를 바꿔야 하면 {@link ErrorCode}의 기본 메시지를 고치거나
 * 코드를 새로 정의할 것.
 */
@Getter
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<String> details;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, List.of());
    }

    public ApiException(ErrorCode errorCode, List<String> details) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.details = details;
    }
}
