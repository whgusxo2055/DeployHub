package com.deployhub.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 이 헬퍼의 존재 이유는 "먼저 실패해도 나머지를 끝까지 기다린다"는 것 하나다 — 안 그러면 skopeo 같은
 * 외부 프로세스가 고아로 남아 정리 중인 파일에 계속 쓴다. {@code invokeAll}을 제출 루프로 바꾸면 깨진다.
 */
class ConcurrencyTest {

    @Test
    void 하나가_실패해도_나머지를_끝까지_기다린_뒤_던진다() {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger finished = new AtomicInteger();
        try {
            assertThatThrownBy(() -> Concurrency.mapAll(pool, List.of(1, 2), item -> {
                        if (item == 1) {
                            throw new IllegalStateException("첫 항목 실패");
                        }
                        sleepQuietly();
                        finished.incrementAndGet();
                        return item;
                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("첫 항목 실패");

            // 실패를 던지기 전에 형제가 이미 끝나 있어야 한다.
            assertThat(finished.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 동시성 상한은 배치가 아니라 풀 크기가 정한다 — 전건을 한 번에 제출해도 넘지 않아야 한다. */
    @Test
    void 전건_제출해도_동시_실행은_풀_크기를_넘지_않는다() {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        try {
            List<Integer> results = Concurrency.mapAll(pool, List.of(1, 2, 3, 4, 5, 6), item -> {
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                sleepQuietly();
                active.decrementAndGet();
                return item;
            });

            assertThat(peak.get()).isEqualTo(2);
            // 호출부(PackageValidationService)가 태그로 짝짓긴 하지만, 순서 보존은 JDK 보장이라 함께 고정한다.
            assertThat(results).containsExactly(1, 2, 3, 4, 5, 6);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
