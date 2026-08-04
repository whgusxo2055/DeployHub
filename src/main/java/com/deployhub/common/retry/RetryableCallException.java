package com.deployhub.common.retry;

import com.deployhub.common.ApiException;
import java.time.Duration;
import java.util.Optional;

/**
 * 외부 호출이 일시적으로 실패해 재시도 대상임을 나타낸다. {@link RetryExecutor}가 재시도
 * 횟수를 소진하면 {@link #giveUpException()}을 그대로 던져 호출자에게 최종 오류를 전달한다.
 * 401/403/404처럼 재시도해도 결과가 바뀌지 않는 오류는 이 예외로 감싸지 않고
 * {@link ApiException}을 바로 던져 재시도 루프를 건너뛴다.
 */
public final class RetryableCallException extends RuntimeException {

    private final ApiException giveUpException;
    private final Duration retryAfterHint;

    public RetryableCallException(ApiException giveUpException) {
        this(giveUpException, null);
    }

    public RetryableCallException(ApiException giveUpException, Duration retryAfterHint) {
        super(giveUpException.getMessage(), giveUpException);
        this.giveUpException = giveUpException;
        this.retryAfterHint = retryAfterHint;
    }

    public ApiException giveUpException() {
        return giveUpException;
    }

    /** 429/503의 {@code Retry-After} 처럼 서버가 명시한 대기 시간. 없으면 설정된 백오프를 따른다. */
    public Optional<Duration> retryAfterHint() {
        return Optional.ofNullable(retryAfterHint);
    }
}
