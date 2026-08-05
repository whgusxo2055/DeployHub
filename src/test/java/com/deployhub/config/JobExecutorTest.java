package com.deployhub.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * "동시 Job 4건 요청 시 3건 실행 + 1건 대기"(구현계획서 439행)는 API 레벨로 재현할 수 없다
 * — JobOrchestrator 단계가 전부 빈 구현이라 즉시 끝나서 큐가 쌓이지 않는다. 대신
 * AsyncConfig가 만드는 실제 executor 설정값(JOB_CONCURRENCY=3, 고정 풀)을 직접 검증한다.
 */
class JobExecutorTest {

    @Test
    void 동시_실행_한도를_넘으면_초과분은_큐에서_대기한다() throws InterruptedException {
        ThreadPoolTaskExecutor executor = new AsyncConfig().jobExecutor(3);
        CountDownLatch started = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);

        try {
            for (int i = 0; i < 4; i++) {
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.getActiveCount()).isEqualTo(3);
            assertThat(executor.getThreadPoolExecutor().getQueue().size()).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
