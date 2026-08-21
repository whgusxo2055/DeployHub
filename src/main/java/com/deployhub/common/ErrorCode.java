package com.deployhub.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 모든 오류 코드. 도메인마다 자기 대역을 쓴다 — 여러 도메인이 "검증 실패" 같은 공용 코드를
 * 나눠 쓰지 않는다({@link ValidatedRequest} 참조).
 *
 * <p><b>문구는 여기에만 있다.</b> 메서드 본문에서 {@code "E-1102: ..."}처럼 문자열로 만들지 말 것 —
 * 같은 사유가 지점마다 다른 문장으로 남고 코드도 어긋난다(실제로 skopeo 미설치가 E-0605로
 * 판정되고도 DB에는 E-0601로 기록됐다).
 *
 * <p><b>{@link Exposure}가 이 코드의 문구가 어디까지 나가는지를 정한다.</b>
 * {@code PUBLIC}은 HTTP 응답이나 무인증 {@code GET /api/package-jobs/{versionName}}의
 * {@code error_message}로 실려 나가므로 <b>서버 경로·호스트·업스트림 응답 본문을 넣지 말 것</b>
 * ({@code ErrorCodeExposureTest}가 검사한다). {@code LOG}는 로그에만 남아 경로를 실어도 된다 —
 * 대신 {@link ApiException}이 생성 시점에 거부하고, {@link #toLogMessage(Object)}만 컨텍스트를 받는다.
 *
 * <p>{@code httpStatus}는 HTTP로 나가는 코드만 갖는다. 같은 코드를 여러 상수가 나눠 쓰는 것은
 * 의도다 — 운영 대응이 같고 문구만 다른 경우다. 대응이 갈리면 코드를 새로 정의할 것.
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
    SUB_VERSION_STATUS_CONTRADICTION(
            "E-0208",
            HttpStatus.BAD_REQUEST,
            "값이 변경되었습니다. 의도한 변경이면 확인 상태를 UPDATED로 바꿔 저장하고,"
                    + " 아니면 최신 값을 다시 조회하세요."),

    // E-03xx 매니페스트 확정 / Job 생성
    INVALID_IMAGE_TAG_SELECTION(
            "E-0301", HttpStatus.BAD_REQUEST, "선택한 image_tag가 메인버전에 없거나 목록 내에 중복됩니다."),
    DUPLICATE_PACKAGE_JOB("E-0302", HttpStatus.CONFLICT, "이미 진행 중이거나 완료된 패키지 Job이 있습니다."),
    NO_PACKAGING_TARGET("E-0303", HttpStatus.BAD_REQUEST, "패키징할 대상이 없습니다."),
    PACKAGING_BLOCKED_BY_PENDING(
            "E-0305", HttpStatus.CONFLICT, "제출 대기(PENDING) 상태인 담당 영역이 있어 패키징을 시작할 수 없습니다."),
    PACKAGE_JOB_NOT_FOUND("E-0306", HttpStatus.NOT_FOUND, "패키지 Job을 찾을 수 없습니다."),
    INVALID_QUERY_PARAMETER("E-0307", HttpStatus.BAD_REQUEST, "요청 파라미터 형식이 올바르지 않습니다."),
    // 생성 응답 안에서 검증을 끝내므로 이 코드가 곧 "Job을 만들지 않았다"는 뜻이다.
    IMAGE_TAG_MISSING_IN_REGISTRY(
            "E-0308", HttpStatus.BAD_REQUEST, "레지스트리에서 확인되지 않는 image_tag가 있어 패키징을 시작할 수 없습니다."),

    // E-04xx 외부 저장소 연동 (NCR: 0401·0402·0404, Graph: 0451~0453)
    REGISTRY_UNAUTHORIZED("E-0401", HttpStatus.UNAUTHORIZED, "레지스트리 인증에 실패했습니다."),
    REGISTRY_TIMEOUT("E-0402", HttpStatus.GATEWAY_TIMEOUT, "레지스트리 호출이 시간 초과되었습니다."),
    REGISTRY_UNREACHABLE("E-0404", HttpStatus.BAD_GATEWAY, "레지스트리에 연결할 수 없습니다."),
    // 항목 실패로도 그대로 쓴다 — 업로드 단계에서 같은 사유가 나면 문구가 같아야 한다.
    GRAPH_TOKEN_ISSUE_FAILED("E-0451", HttpStatus.BAD_GATEWAY, "Microsoft Graph 토큰 발급에 실패했습니다."),
    GRAPH_FORBIDDEN("E-0452", HttpStatus.FORBIDDEN, "Microsoft Graph 권한이 부족합니다."),
    GRAPH_UNAVAILABLE("E-0453", HttpStatus.SERVICE_UNAVAILABLE, "Microsoft Graph가 일시적으로 응답하지 않습니다."),

    // E-05xx 항목 검증
    INVALID_IMAGE_TAG("E-0501", "image_tag 형식이 올바르지 않습니다."),
    IMAGE_NOT_FOUND("E-0501", "레지스트리에 해당 image_tag가 없습니다."),
    MANIFEST_LOOKUP_TIMEOUT("E-0503", "레지스트리 응답 시간 초과로 누락 처리했습니다."),
    MANIFEST_LOOKUP_UNAVAILABLE("E-0503", "레지스트리에 연결할 수 없어 누락 처리했습니다."),

    // E-06xx 다운로드
    SKOPEO_FAILED("E-0601", "이미지 다운로드에 실패했습니다."),
    SKOPEO_TIMEOUT("E-0606", "이미지 다운로드가 시간 초과되었습니다."),
    // 무인증 응답에 실리므로 도구 이름을 숨긴다 — 진단용 원문은 로그에만 남는다(OPERATIONS.md E-0605).
    SKOPEO_NOT_EXECUTABLE("E-0605", "이미지 다운로드 도구를 실행할 수 없습니다."),
    DIGEST_MISMATCH("E-0603", "다운로드된 이미지의 digest가 확정 시점과 다릅니다(재푸시 의심). 자동 재시도 대상이 아닙니다."),
    // "재푸시됨"과 "확인할 수 없음"은 운영 대응이 다르다 — 전자는 재확정, 후자는 그냥 재시도다.
    DIGEST_UNVERIFIABLE("E-0607", "다운로드 직후 digest를 재확인하지 못했습니다."),
    ARCHIVE_UNREADABLE("E-0604", "다운로드 파일을 확인할 수 없습니다."),
    ARCHIVE_EMPTY("E-0604", "다운로드 파일 크기가 0입니다."),
    WORK_DIR_CREATE_FAILED("E-0602", Exposure.LOG, "작업 디렉터리를 만들 수 없습니다."),
    INSUFFICIENT_DISK("E-0602", Exposure.LOG, "디스크 여유 공간이 부족합니다."),

    // E-07xx 수동 재시도
    RETRY_REJECTED_JOB_NOT_FAILED("E-0702", HttpStatus.CONFLICT, "완료되었거나 진행 중인 Job은 재시도할 수 없습니다."),
    WORK_DIR_LOST("E-0703", HttpStatus.CONFLICT, "작업 디렉터리가 소실되었습니다. force=true로 전체 재수집을 진행할 수 있습니다."),

    // E-10xx SharePoint 폴더 (로그 전용)
    FOLDER_LOOKUP_FAILED("E-1001", Exposure.LOG, "폴더 생성 경합 후 재조회에 실패했습니다."),
    ROOT_PATH_MISSING("E-1002", Exposure.LOG, "SharePoint 상위 경로가 없습니다."),
    INVALID_FOLDER_NAME("E-1004", Exposure.LOG, "폴더명에 허용되지 않는 문자가 있습니다."),
    SHARE_LINK_BLOCKED("E-1005", Exposure.LOG, "조직 범위 공유 링크 발급이 차단되어 폴더 webUrl로 대체합니다."),
    SHARE_LINK_REJECTED("E-1005", Exposure.LOG, "공유 링크 발급이 거부됐습니다."),

    // E-11xx 업로드
    UPLOAD_FILE_MISSING("E-0604", "업로드할 파일을 찾을 수 없습니다."),
    UPLOAD_FAILED("E-1101", "SharePoint 업로드에 실패했습니다."),
    UPLOAD_SESSION_GONE("E-1102", Exposure.LOG, "업로드 세션이 소멸했습니다."),
    UPLOAD_FILE_UNREADABLE("E-1102", Exposure.LOG, "업로드 대상 파일을 읽을 수 없습니다."),
    UPLOAD_INCOMPLETE("E-1102", Exposure.LOG, "업로드가 완료되지 않았습니다."),
    UPLOAD_RANGE_MISMATCH("E-1103", Exposure.LOG, "업로드 범위가 반복해서 어긋납니다."),
    INVALID_CHUNK_SIZE(
            "E-1108", Exposure.LOG, "UPLOAD_CHUNK_SIZE는 320 KiB(327680)의 양의 배수여야 하며 60 MiB(62914560) 이하여야 합니다."),

    // E-12xx 결과 제공
    PACKAGE_NOT_READY("E-1201", HttpStatus.CONFLICT, "패키지가 아직 완료되지 않았습니다."),

    // E-14xx 보존·정리
    PACKAGE_CLEANUP_BLOCKED("E-1404", HttpStatus.CONFLICT, "진행 중인 Job의 패키지는 정리할 수 없습니다."),
    // "진행 중"과 "그 사이 재실행됨"은 다른 상황이다 — 메시지가 아니라 코드로 나눈다.
    PACKAGE_CLEANUP_RERUN("E-1405", HttpStatus.CONFLICT, "그 사이 재실행되어 정리 대상이 아닙니다."),
    CLEANUP_SKIPPED("E-1402", Exposure.LOG, "패키지 정리를 건너뜁니다."),
    LOCAL_DIR_DELETE_FAILED("E-1403", Exposure.LOG, "작업 디렉터리 삭제에 실패했습니다. 다음 배치에서 재시도합니다."),

    // E-13xx/E-15xx Job 오케스트레이션 동시성
    JOB_CREATION_CONFLICT("E-1301", HttpStatus.CONFLICT, "동시 요청으로 Job 생성이 충돌했습니다. 다시 시도하세요."),
    JOB_QUEUE_SATURATED("E-1502", HttpStatus.SERVICE_UNAVAILABLE, "실행 대기열이 가득 찼습니다. 잠시 후 다시 시도하세요."),
    ORPHAN_JOB_RESET("E-1501", Exposure.LOG, "고아 Job을 FAILED로 정리합니다."),

    // 미리 분류할 수 없는 오류 전용 — 도메인 코드를 억지로 붙이지 않는다.
    INTERNAL_ERROR("E-9000", HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    // 오타 난 URL을 E-9000으로 흘리면 클라이언트 실수가 서버 장애로 보고된다.
    ENDPOINT_NOT_FOUND("E-9001", HttpStatus.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."),

    // Spring MVC 표준 예외 대역. 이게 없으면 @ExceptionHandler(Exception)가 먼저 잡아
    // 깨진 JSON·잘못된 메서드 같은 클라이언트 실수가 전부 E-9000 + 스택트레이스가 된다.
    MALFORMED_REQUEST("E-9002", HttpStatus.BAD_REQUEST, "요청 본문 또는 파라미터 형식이 올바르지 않습니다."),
    METHOD_NOT_ALLOWED("E-9003", HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE("E-9004", HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다.");

    /** 이 코드의 문구가 어디까지 나가는지. */
    public enum Exposure {
        /** HTTP 응답 본문 또는 {@code package_item.error_message}로 나간다 — 경로·호스트 금지. */
        PUBLIC,
        /** 로그에만 남는다 — 경로·호스트를 실어도 된다. */
        LOG
    }

    private final String code;
    private final HttpStatus httpStatus;
    private final Exposure exposure;
    private final String message;

    ErrorCode(String code, HttpStatus httpStatus, String message) {
        this(code, httpStatus, Exposure.PUBLIC, message);
    }

    /** HTTP로 나가지 않는 코드 — 항목 실패({@code PUBLIC})이거나 로그 전용({@code LOG})이다. */
    ErrorCode(String code, String message) {
        this(code, null, Exposure.PUBLIC, message);
    }

    ErrorCode(String code, Exposure exposure, String message) {
        this(code, null, exposure, message);
    }

    /** 고정 문구만. 어디로 나가도 안전하다. */
    public String toMessage() {
        return "%s: %s".formatted(code, message);
    }

    /**
     * 뒤에 컨텍스트를 붙인다. <b>로그·내부 예외 전용</b> — 경로·호스트가 들어갈 수 있으므로
     * 응답이나 {@code error_message}로 넘기지 말 것.
     */
    public String toLogMessage(Object detail) {
        return "%s: %s: %s".formatted(code, message, detail);
    }
}
