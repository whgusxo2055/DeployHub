package com.deployhub.common;

/**
 * {@code @Valid} 검증에 실패했을 때 어떤 오류 코드로 응답할지 요청 DTO가 스스로 선언한다.
 * 도메인마다 별도 대역(E-01xx, E-02xx, ...)을 쓰므로, 모든 도메인이 하나의 공용
 * "검증 실패" 코드를 나눠 쓰는 일을 막기 위한 장치다. 새 요청 DTO를 추가하면서
 * 이 인터페이스 구현을 빠뜨리면 {@link GlobalExceptionHandler}가 컴파일이 아니라
 * 런타임에 {@link ErrorCode#INTERNAL_ERROR}로 얼버무리게 되므로, 새 DTO를 만들 때
 * 반드시 구현한다.
 */
public interface ValidatedRequest {
    ErrorCode validationErrorCode();
}
