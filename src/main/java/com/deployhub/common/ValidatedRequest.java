package com.deployhub.common;

/**
 * {@code @Valid} 실패 시 어떤 오류 코드로 응답할지 요청 DTO가 스스로 선언한다.
 * 새 요청 DTO는 반드시 구현할 것 — 빠뜨리면 컴파일이 아니라 런타임에
 * {@link ErrorCode#INTERNAL_ERROR}로 얼버무려진다.
 */
public interface ValidatedRequest {
    ErrorCode validationErrorCode();
}
