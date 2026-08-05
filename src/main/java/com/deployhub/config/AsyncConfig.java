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
}
