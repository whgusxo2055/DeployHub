package com.deployhub.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 운영 로그·내부 예외 전용 코드. {@link ErrorCode}는 HTTP 응답, {@link ItemErrorCode}는
 * {@code package_item.error_message}로 나가지만 이쪽은 <b>로그에만</b> 남는다 — 셋 다 코드는
 * enum에만 두고 메서드 본문에서 문자열로 만들지 않는다.
 *
 * <p>같은 코드를 여러 상수가 나눠 쓰는 것은 의도다({@code ItemErrorCode}와 같은 규칙) —
 * 운영 대응이 같고 문구만 다른 경우다. 대응이 갈리면 코드를 새로 정의할 것.
 */
@Getter
@RequiredArgsConstructor
public enum OpsErrorCode {

    // E-06xx 다운로드 준비
    WORK_DIR_CREATE_FAILED("E-0602", "작업 디렉터리를 만들 수 없습니다."),
    INSUFFICIENT_DISK("E-0602", "디스크 여유 공간이 부족합니다."),

    // E-10xx SharePoint 폴더
    FOLDER_LOOKUP_FAILED("E-1001", "폴더 생성 경합 후 재조회에 실패했습니다."),
    ROOT_PATH_MISSING("E-1002", "SharePoint 상위 경로가 없습니다."),
    INVALID_FOLDER_NAME("E-1004", "폴더명에 허용되지 않는 문자가 있습니다."),
    SHARE_LINK_BLOCKED("E-1005", "조직 범위 공유 링크 발급이 차단되어 폴더 webUrl로 대체합니다."),
    SHARE_LINK_REJECTED("E-1005", "공유 링크 발급이 거부됐습니다."),

    // E-11xx 업로드
    UPLOAD_SESSION_GONE("E-1102", "업로드 세션이 소멸했습니다."),
    UPLOAD_FILE_UNREADABLE("E-1102", "업로드 대상 파일을 읽을 수 없습니다."),
    UPLOAD_INCOMPLETE("E-1102", "업로드가 완료되지 않았습니다."),
    UPLOAD_RANGE_MISMATCH("E-1103", "업로드 범위가 반복해서 어긋납니다."),
    INVALID_CHUNK_SIZE("E-1108", "UPLOAD_CHUNK_SIZE는 320 KiB(327680)의 양의 배수여야 하며 60 MiB(62914560) 이하여야 합니다."),

    // E-14xx 보존·정리
    CLEANUP_SKIPPED("E-1402", "패키지 정리를 건너뜁니다."),
    LOCAL_DIR_DELETE_FAILED("E-1403", "작업 디렉터리 삭제에 실패했습니다. 다음 배치에서 재시도합니다."),

    // E-15xx 오케스트레이션
    ORPHAN_JOB_RESET("E-1501", "고아 Job을 FAILED로 정리합니다.");

    private final String code;
    private final String message;

    public String toMessage() {
        return "%s: %s".formatted(code, message);
    }

    /** 뒤에 컨텍스트를 붙인다 — 이 문자열은 로그에만 남으므로 경로·호스트를 실어도 된다. */
    public String toMessage(Object detail) {
        return "%s: %s: %s".formatted(code, message, detail);
    }
}
