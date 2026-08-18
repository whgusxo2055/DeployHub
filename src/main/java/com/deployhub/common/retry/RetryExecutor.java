package com.deployhub.common.retry;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 외부 호출 재시도 정책을 실행하는 공용 실행기. 재시도 여부는 {@link #execute}가 아니라
 * 호출자가 {@link RetryableCallException}으로 감쌀지로 결정한다 —
 * 타임아웃·5xx·429는 감싸고, 401/403/404·디스크 부족은 감싸지 않아 루프를 건너뛴다.
 */
@Slf4j
@Service
public class RetryExecutor {

    private final RetryProperties properties;
    private final Consumer<Duration> sleeper;

    @Autowired
    public RetryExecutor(RetryProperties properties) {
        this(properties, RetryExecutor::sleepOrThrowOnInterrupt);
    }

    /** 테스트가 실제로 대기하지 않도록 대기 동작을 주입하는 생성자. */
    public RetryExecutor(RetryProperties properties, Consumer<Duration> sleeper) {
        this.properties = properties;
        this.sleeper = sleeper;
    }

    public <T> T execute(String operationName, Callable<T> action) {
        int attempt = 0;
        while (true) {
            try {
                return action.call();
            } catch (RetryableCallException ex) {
                attempt++;
                if (attempt > properties.maxRetries()) {
                    log.warn("{} 재시도 {}회 모두 실패, 포기합니다.", operationName, properties.maxRetries());
                    throw ex.giveUpException();
                }
                int currentAttempt = attempt;
                Duration wait = ex.retryAfterHint().orElseGet(() -> properties.backoffFor(currentAttempt));
                log.warn(
                        "{} 실패 ({}/{}), {} 후 재시도합니다.",
                        operationName,
                        attempt,
                        properties.maxRetries(),
                        wait);
                sleeper.accept(wait);
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(operationName + " 실행 중 예기치 못한 오류", ex);
            }
        }
    }

    /**
     * 백오프 대기. 인터럽트를 삼키지 않고 플래그를 복원한 뒤 던진다(Guava 동명 메서드와 반대다).
     * ponytail: Thread.sleep 블로킹이라 @Async 워커 안에서는 백오프 동안 풀 스레드를 하나 묶는다 —
     * JOB_CONCURRENCY(3)로는 감당되지만 더 키우려면 스케줄러 기반 비블로킹으로 바꿀 것.
     */
    public static void sleepOrThrowOnInterrupt(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재시도 대기 중 인터럽트되었습니다.", e);
        }
    }
}
