package com.deployhub.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Job·매니페스트·다운로드 전용 실행기. 전부 고정 풀(core=max)이라 초과분은 큐에서 대기한다 —
 * 별도 세마포어나 DB 큐 테이블을 두지 않는다. {@code waitForTasksToCompleteOnShutdown}은 켜지 않는다:
 * 강제 종료된 Job은 재기동 시 {@link com.deployhub.job.service.OrphanJobCleaner}가 FAILED로 정리한다.
 *
 * <p><b>주의</b>: {@code Executor} 빈을 정의하면 Boot의 기본 {@code applicationTaskExecutor}
 * 자동 구성이 꺼진다 — 실행기를 지정하지 않은 {@code @Async}는 조용히 이 풀을 나눠 쓰게 되므로
 * 새 {@code @Async}에는 반드시 한정자를 명시할 것.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("jobExecutor")
    public ThreadPoolTaskExecutor jobExecutor(@Value("${deployhub.job.concurrency:3}") int concurrency) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("job-");
        executor.initialize();
        return executor;
    }

    /**
     * 매니페스트 조회 전용. {@code @Async}가 아니라 {@code ImageTagChecker}가 전건 제출하므로 풀
     * 크기가 곧 동시 조회 수다. 큐는 무제한(항목당 클로저 하나) — 과부하 차단은 {@code jobExecutor}가 맡는다.
     */
    @Bean(name = "manifestExecutor", destroyMethod = "shutdownNow")
    public ExecutorService manifestExecutor(@Value("${deployhub.manifest.concurrency:5}") int concurrency) {
        return fixedPool(concurrency, "manifest-");
    }

    /** 서버 전체의 동시 skopeo 수 상한. Job별로 나누지 않으므로 Job 하나가 한가한 슬롯을 다 써도 된다. */
    @Bean(name = "downloadExecutor", destroyMethod = "shutdownNow")
    public ExecutorService downloadExecutor(@Value("${deployhub.download.concurrency:9}") int concurrency) {
        return fixedPool(concurrency, "download-");
    }

    /** {@code shutdownNow}로 파괴한다 — 기본 추론값 {@code shutdown()}은 4GB 다운로드가 끝날 때까지 종료를 막는다. */
    private static ExecutorService fixedPool(int size, String threadNamePrefix) {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(
                size, runnable -> new Thread(runnable, threadNamePrefix + counter.incrementAndGet()));
    }
}
