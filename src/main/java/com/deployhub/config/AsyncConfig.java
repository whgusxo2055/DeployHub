package com.deployhub.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Job 오케스트레이션 전용 실행기 (구현계획서 Phase 3-2, JOB_CONCURRENCY 기본 3). 고정 풀
 * (core=max)로 두어 4번째 이후 Job은 큐에서 PENDING인 채 대기한다 — 별도 세마포어나
 * DB 큐 테이블을 두지 않는다.
 *
 * <p>{@code waitForTasksToCompleteOnShutdown}은 켜지 않는다 — 강제 종료 시 진행 중이던
 * Job은 그대로 죽게 두고, 재기동 시 {@link com.deployhub.job.service.OrphanJobCleaner}가
 * FAILED로 정리하는 쪽이 구현계획서 417행이 의도한 설계다.
 *
 * <p>ponytail: {@code Executor} 빈을 정의하면 Boot의 기본 {@code applicationTaskExecutor}
 * 자동 구성이 꺼진다({@code @ConditionalOnMissingBean(Executor.class)}). 지금은
 * {@code @Async}를 쓰는 곳이 {@link com.deployhub.job.service.JobOrchestrator} 하나뿐이라
 * 무해하지만, Phase 4~6에서 실행기 지정 없이 {@code @Async}를 하나 더 추가하면 그 작업이
 * 조용히 이 3스레드 Job 전용 풀을 나눠 쓰게 된다 — 새 {@code @Async}는 반드시
 * {@code @Async("jobExecutor")}로 명시하거나 별도 실행기 빈을 추가할 것.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    // 구현계획서 456행 "동시 요청 5건 제한" — 0.6절 설정값 표에 없는 값이라 env var로
    // 노출하지 않고 상수로 고정한다. NCR 전체가 공유하는 전역 상한이다(Job별이 아님).
    private static final int MANIFEST_CHECK_CONCURRENCY = 5;

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

    /** FN-05 매니페스트 조회 전용 (Phase 4). {@code @Async}가 아니라 서비스가 직접 제출한다. */
    @Bean("manifestExecutor")
    public ThreadPoolTaskExecutor manifestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(MANIFEST_CHECK_CONCURRENCY);
        executor.setMaxPoolSize(MANIFEST_CHECK_CONCURRENCY);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("manifest-");
        executor.initialize();
        return executor;
    }

    /**
     * FN-06-1 다운로드 전용 (Phase 4). 풀 크기를 {@code JOB_CONCURRENCY × DOWNLOAD_CONCURRENCY}로
     * 잡아 Job 전체가 공유한다 — Job당 별도 풀을 두지 않아도 "Job 3건 × 아이템 3건 = skopeo
     * 최대 9개 동시"(구현계획서 470행)가 자연히 성립한다(Job 하나가 한 번에 최대
     * DOWNLOAD_CONCURRENCY개만 제출하고 완료를 기다린 뒤 다음 배치를 제출하므로).
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
