package com.deployhub.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 구현계획서 오류 코드 체계(E-01xx: 메인버전, E-02xx: 서브버전 등). 각 도메인이 자기
 * 대역 안에서 구체적인 코드를 갖는다 — 여러 도메인이 "요청 값 검증 실패" 같은 하나의
 * 공용 코드를 나눠 쓰지 않는다 ({@link com.deployhub.common.ValidatedRequest} 참조).
 * Phase 1 범위에서 쓰는 코드만 정의한다. 이후 Phase가 각자의 대역(E-03xx ~ E-15xx)을 채운다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // E-01xx 메인버전
    MAIN_VERSION_NOT_FOUND("E-0101", HttpStatus.NOT_FOUND, "메인버전을 찾을 수 없습니다."),
    MAIN_VERSION_ALREADY_EXISTS("E-0102", HttpStatus.CONFLICT, "이미 등록된 메인버전입니다."),
    MAIN_VERSION_VALIDATION_FAILED("E-0103", HttpStatus.BAD_REQUEST, "메인버전 요청 값이 올바르지 않습니다."),

    // E-02xx 서브버전
    SUB_VERSION_NOT_FOUND("E-0201", HttpStatus.NOT_FOUND, "서브버전을 찾을 수 없습니다."),
    NO_SUB_VERSIONS("E-0202", HttpStatus.CONFLICT, "등록된 서브버전이 없어 패키징 대상을 확정할 수 없습니다."),
    DUPLICATE_IMAGE_TAG("E-0203", HttpStatus.BAD_REQUEST, "메인버전 내에 동일한 image_tag가 이미 존재합니다."),
    MANIFEST_LOCKED("E-0204", HttpStatus.CONFLICT, "이미 패키징 Job이 진행 중이거나 완료된 메인버전은 수정할 수 없습니다."),
    SUB_VERSION_VALIDATION_FAILED("E-0205", HttpStatus.BAD_REQUEST, "서브버전 요청 값이 올바르지 않습니다."),

    // 어느 도메인 대역에도 속할 수 없는, 예상치 못한 오류에만 쓰는 유일한 예외.
    // 미리 분류할 수 없는 오류이므로 도메인 코드를 억지로 붙이지 않는다.
    INTERNAL_ERROR("E-9000", HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
