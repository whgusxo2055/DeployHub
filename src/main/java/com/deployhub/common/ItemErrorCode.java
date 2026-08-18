package com.deployhub.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 항목({@code package_item}) 실패 사유. {@link ErrorCode}가 HTTP 응답용이라면 이쪽은 Job이 항목
 * 하나를 FAILED로 기록할 때 쓰는 코드다 — 응답 상태를 가르지 않아 {@code HttpStatus}가 없다.
 *
 * <p><b>문구는 여기에만 있다</b> — 호출부가 자유 문자열을 만들면 같은 사유가 지점마다 다른 문장으로
 * 남고, 코드도 어긋난다(실제로 skopeo 미설치가 E-0605로 판정되고도 DB에는 E-0601로 기록됐다).
 *
 * <p>여기 문구는 무인증 {@code GET /api/package-jobs/{versionName}} 응답에 그대로 실린다 —
 * 서버 경로·호스트·업스트림 응답 본문을 넣지 말 것. 상세는 {@code detail}로 넘겨 로그에만 남긴다.
 */
@Getter
@RequiredArgsConstructor
public enum ItemErrorCode {

    // E-05xx 검증
    INVALID_IMAGE_TAG("E-0501", "image_tag 형식이 올바르지 않습니다."),
    IMAGE_NOT_FOUND("E-0501", "레지스트리에 이미지가 존재하지 않습니다."),
    MANIFEST_LOOKUP_TIMEOUT("E-0503", "레지스트리 응답 시간 초과로 누락 처리했습니다."),
    MANIFEST_LOOKUP_UNAVAILABLE("E-0503", "레지스트리에 연결할 수 없어 누락 처리했습니다."),

    // E-06xx 다운로드
    SKOPEO_FAILED("E-0601", "이미지 다운로드에 실패했습니다."),
    SKOPEO_TIMEOUT("E-0606", "이미지 다운로드가 시간 초과되었습니다."),
    SKOPEO_NOT_EXECUTABLE("E-0605", "skopeo를 실행할 수 없습니다."),
    DIGEST_MISMATCH("E-0603", "다운로드된 이미지의 digest가 확정 시점과 다릅니다(재푸시 의심). 자동 재시도 대상이 아닙니다."),
    // "재푸시됨"과 "확인할 수 없음"은 운영 대응이 다르다 — 전자는 재확정, 후자는 그냥 재시도다.
    DIGEST_UNVERIFIABLE("E-0607", "다운로드 직후 digest를 재확인하지 못했습니다."),
    ARCHIVE_UNREADABLE("E-0604", "다운로드 파일을 확인할 수 없습니다."),
    ARCHIVE_EMPTY("E-0604", "다운로드 파일 크기가 0입니다."),

    // E-11xx 업로드
    UPLOAD_FILE_MISSING("E-0604", "업로드할 파일을 찾을 수 없습니다."),
    UPLOAD_TOKEN_FAILED("E-0451", "Microsoft Graph 토큰 발급에 실패했습니다."),
    UPLOAD_FORBIDDEN("E-0452", "Microsoft Graph 권한이 부족합니다."),
    UPLOAD_UNAVAILABLE("E-0453", "Microsoft Graph가 일시적으로 응답하지 않습니다."),
    UPLOAD_FAILED("E-1101", "SharePoint 업로드에 실패했습니다.");

    private final String code;
    private final String message;

    /** {@code package_item.error_message}에 저장되는 형식. */
    public String toErrorMessage() {
        return "%s: %s".formatted(code, message);
    }
}
