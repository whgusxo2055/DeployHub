package com.deployhub.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 구현계획서 오류 코드 체계(E-01xx: 메인버전, E-02xx: 서브버전 등). 각 도메인이 자기
 * 대역 안에서 구체적인 코드를 갖는다 — 여러 도메인이 "요청 값 검증 실패" 같은 하나의
 * 공용 코드를 나눠 쓰지 않는다 ({@link com.deployhub.common.ValidatedRequest} 참조).
 * 각 Phase가 진행되면서 자기 대역(E-03xx ~ E-15xx)을 채운다. 아직 쓰지 않는 코드는
 * 정의하지 않는다 — Phase 4까지는 E-01xx·E-02xx·E-03xx(일부)·E-04xx·E-0702·E-0703·
 * E-1301·E-1502만 존재한다. E-05xx/E-06xx/E-0701은 HTTP로 나가지 않고
 * {@code package_item.error_message}에만 문자열로 남으므로 이 enum에 없다(E-0403 참고).
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

    // E-03xx 매니페스트 확정 / Job 생성 (Phase 3, FN-03·FN-11 중복방지)
    INVALID_IMAGE_TAG_SELECTION(
            "E-0301", HttpStatus.BAD_REQUEST, "선택한 image_tag가 메인버전에 없거나 목록 내에 중복됩니다."),
    DUPLICATE_PACKAGE_JOB("E-0302", HttpStatus.CONFLICT, "이미 진행 중이거나 완료된 패키지 Job이 있습니다."),
    NO_PACKAGING_TARGET("E-0303", HttpStatus.BAD_REQUEST, "패키징할 대상이 없습니다."),
    INSUFFICIENT_DISK_SPACE("E-0304", HttpStatus.CONFLICT, "작업 디렉터리의 여유 공간이 부족합니다."),
    // 구현계획서 주요 예외 목록에 없는 코드다 — PENDING 서브버전 때문에 확정이 막히는
    // 사유가 E-0202(서브버전 0건)·E-0303(대상 0건)과 의미가 달라 새로 부여했다.
    PACKAGING_BLOCKED_BY_PENDING(
            "E-0305", HttpStatus.CONFLICT, "제출 대기(PENDING) 상태인 담당 영역이 있어 패키징을 시작할 수 없습니다."),
    PACKAGE_JOB_NOT_FOUND("E-0306", HttpStatus.NOT_FOUND, "패키지 Job을 찾을 수 없습니다."),
    INVALID_QUERY_PARAMETER("E-0307", HttpStatus.BAD_REQUEST, "요청 파라미터 형식이 올바르지 않습니다."),

    // E-04xx 외부 저장소 연동 (NCR: 0401~0404, Graph: 0451~0453).
    // E-0403(자격 증명 로드 실패 → 기동 실패)은 여기 없다 — HTTP 응답으로 나가는 코드가
    // 아니라 NcrProperties/GraphProperties의 @NotBlank 검증이 기동 자체를 막는 방식으로
    // 대신한다(StartupChecks 참고).
    REGISTRY_UNAUTHORIZED("E-0401", HttpStatus.UNAUTHORIZED, "레지스트리 인증에 실패했습니다."),
    REGISTRY_TIMEOUT("E-0402", HttpStatus.GATEWAY_TIMEOUT, "레지스트리 호출이 시간 초과되었습니다."),
    REGISTRY_UNREACHABLE("E-0404", HttpStatus.BAD_GATEWAY, "NCR Private Endpoint에 연결할 수 없습니다."),
    GRAPH_TOKEN_ISSUE_FAILED("E-0451", HttpStatus.BAD_GATEWAY, "Microsoft Graph 토큰 발급에 실패했습니다."),
    GRAPH_FORBIDDEN("E-0452", HttpStatus.FORBIDDEN, "Microsoft Graph 권한이 부족합니다."),
    GRAPH_UNAVAILABLE("E-0453", HttpStatus.SERVICE_UNAVAILABLE, "Microsoft Graph가 일시적으로 응답하지 않습니다."),

    // E-07xx FN-07 수동 재시도 (Phase 4). E-0701(MAX_RETRY 초과)은 여기 없다 — E-0403과
    // 같은 이유로, HTTP 응답이 아니라 package_item.error_message에만 남는 코드다.
    RETRY_REJECTED_JOB_NOT_FAILED("E-0702", HttpStatus.CONFLICT, "완료되었거나 진행 중인 Job은 재시도할 수 없습니다."),
    WORK_DIR_LOST("E-0703", HttpStatus.CONFLICT, "작업 디렉터리가 소실되었습니다. force=true로 전체 재수집을 진행할 수 있습니다."),

    // E-12xx 결과 제공 (Phase 6, FN-10).
    PACKAGE_NOT_READY("E-1201", HttpStatus.CONFLICT, "패키지가 아직 완료되지 않았습니다."),

    // E-14xx 보존·정리 (Phase 6, FN-11). E-1401(404 이미 삭제됨 → 정상 간주)·E-1402(403 →
    // 해당 건만 건너뜀)·E-1403(디렉터리 삭제 실패 → 다음 배치 재시도)은 여기 없다 —
    // E-1501과 같은 이유로 배치 내부에서 로그로만 남고 HTTP로 나가지 않는다.
    // E-1404는 구현계획서 주요 예외 목록에 없는 코드다 — 수동 정리 API가 진행 중인 Job을
    // 거부하는 사유가 E-14xx 어느 것과도 겹치지 않아 새로 부여했다(E-0305 선례).
    PACKAGE_CLEANUP_BLOCKED("E-1404", HttpStatus.CONFLICT, "진행 중인 Job의 패키지는 정리할 수 없습니다."),

    // E-13xx/E-15xx Job 오케스트레이션 동시성 (Phase 3). E-1501(기동 시 고아 Job 정리)은
    // 여기 없다 — E-0403과 같은 이유로, HTTP 응답으로 나가지 않고 OrphanJobCleaner가
    // 로그에만 남기는 코드다.
    JOB_CREATION_CONFLICT("E-1301", HttpStatus.CONFLICT, "동시 요청으로 Job 생성이 충돌했습니다. 다시 시도하세요."),
    JOB_QUEUE_SATURATED("E-1502", HttpStatus.SERVICE_UNAVAILABLE, "실행 대기열이 가득 찼습니다. 잠시 후 다시 시도하세요."),

    // 어느 도메인 대역에도 속할 수 없는, 예상치 못한 오류에만 쓰는 유일한 예외.
    // 미리 분류할 수 없는 오류이므로 도메인 코드를 억지로 붙이지 않는다.
    INTERNAL_ERROR("E-9000", HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    // 구현계획서 주요 예외 목록에 없는 코드다 — 오타 난 URL은 어느 도메인에도 속하지
    // 않는데, 이걸 E-9000으로 흘리면 클라이언트 실수가 서버 장애로 보고된다(Phase 7
    // 실서버 검증에서 발견).
    ENDPOINT_NOT_FOUND("E-9001", HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다.");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;
}
