package com.deployhub.common.retry;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 구현계획서 0.6절 {@code MAX_RETRY} / {@code RETRY_BACKOFF}. 외부 호출 전체가 공유하는
 * 단일 재시도 정책이다 (Phase 2 작업 항목 3). {@code maxRetries}는 최초 시도 이후 추가로
 * 허용하는 재시도 횟수이며(총 시도 = 1 + maxRetries), 0을 명시적으로 허용해 재시도를
 * 끌 수 있다 — {@code application.yml}이 항상 기본값({@code MAX_RETRY:3})을 공급하므로
 * "미설정"과 "0으로 설정"을 구분할 필요가 없어 별도 보정을 두지 않는다.
 */
@ConfigurationProperties(prefix = "deployhub.retry")
public record RetryProperties(int maxRetries, List<Duration> backoff) {

    public RetryProperties {
        if (backoff == null || backoff.isEmpty()) {
            backoff = List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(45));
        }
    }

    public Duration backoffFor(int attempt) {
        int index = Math.min(attempt - 1, backoff.size() - 1);
        return backoff.get(index);
    }
}
