package com.deployhub.common.retry;

import java.time.Duration;
import org.springframework.http.HttpHeaders;

/**
 * 429/503 응답의 {@code Retry-After}(초 단위) 헤더를 백오프 힌트로 변환한다. 업스트림이
 * 보낸 값을 그대로 신뢰하지 않는다 — 음수/0이면 기본 백오프를 쓰도록 무시하고, 과도하게 큰
 * 값(예: 하루)에 스레드가 묶이지 않도록 60초로 clamp한다.
 */
public final class RetryAfterHeader {

    private static final long MAX_SECONDS = 60;

    private RetryAfterHeader() {}

    public static Duration parseSeconds(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds <= 0) {
                return null;
            }
            return Duration.ofSeconds(Math.min(seconds, MAX_SECONDS));
        } catch (NumberFormatException e) {
            // HTTP-date 형식(RFC 7231)은 지원하지 않는다 — 기본 백오프로 폴백.
            return null;
        }
    }
}
