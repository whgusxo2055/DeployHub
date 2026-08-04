package com.deployhub.common.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 실제로 대기하지 않도록 대기 동작(Consumer&lt;Duration&gt;)을 기록만 하는 가짜로 대체한다. */
class RetryExecutorTest {

    private final List<Duration> recordedWaits = new ArrayList<>();
    private final RetryExecutor executor =
            new RetryExecutor(new RetryProperties(3, List.of(Duration.ofSeconds(5), Duration.ofSeconds(15))), recordedWaits::add);

    @Test
    void 성공하면_재시도_없이_결과를_반환한다() {
        String result = executor.execute("test", () -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(recordedWaits).isEmpty();
    }

    @Test
    void 재시도_가능한_실패는_설정된_횟수만큼_재시도한다() {
        AtomicInteger attempts = new AtomicInteger();

        String result = executor.execute("test", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RetryableCallException(new ApiException(ErrorCode.REGISTRY_TIMEOUT));
            }
            return "recovered";
        });

        assertThat(result).isEqualTo("recovered");
        assertThat(attempts.get()).isEqualTo(3);
        // 설정된 백오프(5s, 15s)를 순서대로 사용
        assertThat(recordedWaits).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(15));
    }

    @Test
    void 재시도를_모두_소진하면_원래_예외를_던진다() {
        ApiException giveUp = new ApiException(ErrorCode.REGISTRY_TIMEOUT);

        assertThatThrownBy(() -> executor.execute("test", () -> {
                    throw new RetryableCallException(giveUp);
                }))
                .isSameAs(giveUp);
    }

    @Test
    void Retry_After_힌트가_있으면_설정된_백오프_대신_그_값을_쓴다() {
        AtomicInteger attempts = new AtomicInteger();

        executor.execute("test", () -> {
            if (attempts.incrementAndGet() < 2) {
                throw new RetryableCallException(
                        new ApiException(ErrorCode.GRAPH_UNAVAILABLE), Duration.ofSeconds(1));
            }
            return "ok";
        });

        assertThat(recordedWaits).containsExactly(Duration.ofSeconds(1));
    }
}
