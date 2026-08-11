package com.deployhub.common.retry;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 외부 호출 전체가 공유하는 단일 재시도 정책. {@code maxRetries}는 최초 시도 <b>이후</b> 추가
 * 횟수라 총 시도는 {@code 1 + maxRetries}이고, 0이면 재시도가 꺼진다.
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
