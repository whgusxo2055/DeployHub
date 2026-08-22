package com.deployhub.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 이 클래스의 존재 이유는 "먼저 실패해도 나머지를 끝까지 기다린다"는 것 하나다 —
 * 안 그러면 skopeo 같은 외부 프로세스가 고아로 남아 정리 중인 파일에 계속 쓴다.
 */
class BoundedParallelismTest {

    @Test
    void 하나가_실패해도_나머지를_끝까지_기다린_뒤_던진다() {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger finished = new AtomicInteger();
        try {
            assertThatThrownBy(() -> BoundedParallelism.mapInBatches(List.of(1, 2), 2, pool, item -> {
                        if (item == 1) {
                            throw new IllegalStateException("첫 항목 실패");
                        }
                        sleepQuietly();
                        finished.incrementAndGet();
                        return item;
                    }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("첫 항목 실패");

            assertThat(finished.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 제출이_거부돼도_이미_시작된_작업을_모두_기다린다() {
        // 스트림으로 한 번에 제출하면 거부 시점에 futures가 만들어지지 않아, 이미 돌기 시작한
        // 작업을 join하지 못한 채 예외가 나간다 — 위 보장이 정확히 그때 깨진다.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger submitted = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        CountDownLatch running = new CountDownLatch(2);
        Executor rejectingAfterTwo = runnable -> {
            if (submitted.incrementAndGet() > 2) {
                throw new RejectedExecutionException("큐가 가득 찼습니다");
            }
            pool.execute(runnable);
        };

        try {
            assertThatThrownBy(() -> BoundedParallelism.mapInBatches(
                            List.of(1, 2, 3), 3, rejectingAfterTwo, item -> {
                                running.countDown();
                                sleepQuietly();
                                finished.incrementAndGet();
                                return item;
                            }))
                    .isInstanceOf(RejectedExecutionException.class);

            assertThat(finished.get()).isEqualTo(2);
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
