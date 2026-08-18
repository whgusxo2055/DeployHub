package com.deployhub.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * HTTP 응답으로 나가는 오류 코드. 도메인마다 자기 대역을 쓴다 — 여러 도메인이 "검증 실패" 같은
 * 공용 코드를 나눠 쓰지 않는다({@link ValidatedRequest} 참조).
 * 여기 없는 코드는 HTTP로 안 나가는 것들이다 — 항목 실패는 {@code package_item.error_message}에,
 * 기동 검증 실패는 예외 메시지에, 배치 내부 사유는 로그에만 남는다.
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
    DUPLICATE_IMAGE_TAG("E-0203", HttpStatus.BAD_REQUEST, "메인버전 내에 동일한 image_tag가 이미 존재합니다."),
    MANIFEST_LOCKED("E-0204", HttpStatus.CONFLICT, "이미 패키징 Job이 진행 중이거나 완료된 메인버전은 수정할 수 없습니다."),
    SUB_VERSION_VALIDATION_FAILED("E-0205", HttpStatus.BAD_REQUEST, "서브버전 요청 값이 올바르지 않습니다."),
    IMAGE_TAG_NOT_IN_REGISTRY("E-0206", HttpStatus.BAD_REQUEST, "레지스트리에 없는 image_tag가 있습니다."),
    SUB_VERSION_SAVE_CONFLICT("E-0207", HttpStatus.CONFLICT, "동시 요청으로 서브버전 저장이 충돌했습니다. 다시 시도하세요."),

    // E-03xx 매니페스트 확정 / Job 생성
    INVALID_IMAGE_TAG_SELECTION(
            "E-0301", HttpStatus.BAD_REQUEST, "선택한 image_tag가 메인버전에 없거나 목록 내에 중복됩니다."),
    DUPLICATE_PACKAGE_JOB("E-0302", HttpStatus.CONFLICT, "이미 진행 중이거나 완료된 패키지 Job이 있습니다."),
    NO_PACKAGING_TARGET("E-0303", HttpStatus.BAD_REQUEST, "패키징할 대상이 없습니다."),
    INSUFFICIENT_DISK_SPACE(
            "E-0304", HttpStatus.CONFLICT, "작업 디렉터리의 여유 공간이 부족합니다. force=true로 진행할 수 있습니다."),
    PACKAGING_BLOCKED_BY_PENDING(
            "E-0305", HttpStatus.CONFLICT, "제출 대기(PENDING) 상태인 담당 영역이 있어 패키징을 시작할 수 없습니다."),
    PACKAGE_JOB_NOT_FOUND("E-0306", HttpStatus.NOT_FOUND, "패키지 Job을 찾을 수 없습니다."),
    INVALID_QUERY_PARAMETER("E-0307", HttpStatus.BAD_REQUEST, "요청 파라미터 형식이 올바르지 않습니다."),

    // E-04xx 외부 저장소 연동 (NCR: 0401·0402·0404, Graph: 0451~0453)
    REGISTRY_UNAUTHORIZED("E-0401", HttpStatus.UNAUTHORIZED, "레지스트리 인증에 실패했습니다."),
    REGISTRY_TIMEOUT("E-0402", HttpStatus.GATEWAY_TIMEOUT, "레지스트리 호출이 시간 초과되었습니다."),
    REGISTRY_UNREACHABLE("E-0404", HttpStatus.BAD_GATEWAY, "NCR Private Endpoint에 연결할 수 없습니다."),
    GRAPH_TOKEN_ISSUE_FAILED("E-0451", HttpStatus.BAD_GATEWAY, "Microsoft Graph 토큰 발급에 실패했습니다."),
    GRAPH_FORBIDDEN("E-0452", HttpStatus.FORBIDDEN, "Microsoft Graph 권한이 부족합니다."),
    GRAPH_UNAVAILABLE("E-0453", HttpStatus.SERVICE_UNAVAILABLE, "Microsoft Graph가 일시적으로 응답하지 않습니다."),

    // E-07xx 수동 재시도
    RETRY_REJECTED_JOB_NOT_FAILED("E-0702", HttpStatus.CONFLICT, "완료되었거나 진행 중인 Job은 재시도할 수 없습니다."),
    WORK_DIR_LOST("E-0703", HttpStatus.CONFLICT, "작업 디렉터리가 소실되었습니다. force=true로 전체 재수집을 진행할 수 있습니다."),

    // E-12xx 결과 제공
    PACKAGE_NOT_READY("E-1201", HttpStatus.CONFLICT, "패키지가 아직 완료되지 않았습니다."),

    // E-14xx 보존·정리
    PACKAGE_CLEANUP_BLOCKED("E-1404", HttpStatus.CONFLICT, "진행 중인 Job의 패키지는 정리할 수 없습니다."),
    // "진행 중"과 "그 사이 재실행됨"은 다른 상황이다 — 메시지가 아니라 코드로 나눈다.
    PACKAGE_CLEANUP_RERUN("E-1405", HttpStatus.CONFLICT, "그 사이 재실행되어 정리 대상이 아닙니다."),

    // E-13xx/E-15xx Job 오케스트레이션 동시성
    JOB_CREATION_CONFLICT("E-1301", HttpStatus.CONFLICT, "동시 요청으로 Job 생성이 충돌했습니다. 다시 시도하세요."),
    JOB_QUEUE_SATURATED("E-1502", HttpStatus.SERVICE_UNAVAILABLE, "실행 대기열이 가득 찼습니다. 잠시 후 다시 시도하세요."),

    // 미리 분류할 수 없는 오류 전용 — 도메인 코드를 억지로 붙이지 않는다.
    INTERNAL_ERROR("E-9000", HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    // 오타 난 URL을 E-9000으로 흘리면 클라이언트 실수가 서버 장애로 보고된다.
    ENDPOINT_NOT_FOUND("E-9001", HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),

    // Spring MVC 표준 예외 대역. 이게 없으면 @ExceptionHandler(Exception)가 먼저 잡아
    // 깨진 JSON·잘못된 메서드 같은 클라이언트 실수가 전부 E-9000 + 스택트레이스가 된다.
    MALFORMED_REQUEST("E-9002", HttpStatus.BAD_REQUEST, "요청 본문 또는 파라미터 형식이 올바르지 않습니다."),
    METHOD_NOT_ALLOWED("E-9003", HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE("E-9004", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
