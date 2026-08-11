package com.deployhub.config;

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
     * 매니페스트 조회 전용. {@code @Async}가 아니라 서비스가 직접 제출한다 — 풀 크기와
     * 배치 크기가 같아야 해서 {@code PackageValidationService}가 같은 프로퍼티를 읽는다.
     */
    @Bean("manifestExecutor")
    public ThreadPoolTaskExecutor manifestExecutor(@Value("${deployhub.manifest.concurrency:5}") int concurrency) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("manifest-");
        executor.initialize();
        return executor;
    }

    /**
     * 다운로드 전용. 풀 크기가 {@code JOB_CONCURRENCY × DOWNLOAD_CONCURRENCY}라 Job 전체가 공유해도
     * "Job당 최대 DOWNLOAD_CONCURRENCY개 동시"가 성립한다 — Job이 한 배치를 끝내야 다음을 제출하기 때문이다.
     */
    @Bean("downloadExecutor")
    public ThreadPoolTaskExecutor downloadExecutor(
            @Value("${deployhub.job.concurrency:3}") int jobConcurrency,
            @Value("${deployhub.download.concurrency:3}") int downloadConcurrency) {
        int size = jobConcurrency * downloadConcurrency;
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("download-");
        executor.initialize();
        return executor;
    }
}
