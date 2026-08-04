package com.deployhub.common.retry;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 구현계획서 0.6절 재시도 정책을 실행하는 공용 실행기. 타임아웃·5xx·429는 재시도하고
 * 401/403/404/디스크 부족은 재시도하지 않는다 — 이 구분은 각 호출자가
 * {@link RetryableCallException}으로 감쌀지 여부로 결정한다({@link #execute} 자체는
 * 재시도 여부를 판단하지 않는다). {@link RetryProperties}는
 * {@code DeployHubApplication}의 {@code @ConfigurationPropertiesScan}으로 빈 등록된다.
 */
@Slf4j
@Component
public class RetryExecutor {

    private final RetryProperties properties;
    private final Consumer<Duration> sleeper;

    @Autowired
    public RetryExecutor(RetryProperties properties) {
        this(properties, RetryExecutor::sleepUninterruptibly);
    }

    /** 테스트에서 실제로 대기하지 않도록 대기 동작을 주입할 수 있게 여는 생성자. */
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
                        "{} 실패 ({}/{}), {}초 후 재시도합니다.",
                        operationName,
                        attempt,
                        properties.maxRetries(),
                        wait.toSeconds());
                sleeper.accept(wait);
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalStateException(operationName + " 실행 중 예기치 못한 오류", ex);
            }
        }
    }

    // ponytail: Thread.sleep으로 블로킹 대기한다. Phase 2(헬스체크·기동 점검)는 문제없지만
    // Phase 4/5가 @Async Job 워커 안에서 재사용하면 백오프 동안 스레드 풀 스레드를 하나 묶어둔다.
    // JOB_CONCURRENCY(기본 3)로는 감당되지만, 그 이상으로 늘려야 하면 리액티브/스케줄러 기반
    // 비블로킹 재시도로 바꿀 것.
    private static void sleepUninterruptibly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재시도 대기 중 인터럽트되었습니다.", e);
        }
    }
}
