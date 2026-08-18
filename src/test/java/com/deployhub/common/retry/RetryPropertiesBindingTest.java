package com.deployhub.common.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;

/**
 * {@code application.yml}을 실제로 바인딩해 백오프가 <b>초</b>로 읽히는지 본다.
 *
 * <p>{@link RetryExecutorTest}는 {@code new RetryProperties(...)}로 Duration을 직접 만들어 이 경로를
 * 지나지 않는다 — 그래서 "단위 없는 5는 5밀리초"라는 Boot 기본 동작이 오래 숨어 있었다. 값이 아니라
 * <b>설정 파일</b>을 검증하는 테스트가 따로 필요한 이유다.
 */
class RetryPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(EnableRetryProperties.class);

    /** {@code backoff: 5s,15s,45s}에서 단위를 지우면(5,15,45) 이 단언이 밀리초로 떨어져 실패한다. */
    @Test
    void application_yml의_백오프는_초_단위로_바인딩된다() {
        runner.run(assertBackoff(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(45)));
    }

    /** 운영자가 단위를 빠뜨린 값을 넣어도 밀리초로 읽히지 않는다(@DurationUnit). */
    @Test
    void 단위_없는_설정값도_초로_읽는다() {
        runner.withPropertyValues("deployhub.retry.backoff=7,21")
                .run(assertBackoff(Duration.ofSeconds(7), Duration.ofSeconds(21)));
    }

    private static ContextConsumer<AssertableApplicationContext> assertBackoff(Duration... expected) {
        return context -> {
            RetryProperties properties = context.getBean(RetryProperties.class);
            assertThat(properties.backoff()).containsExactly(expected);
            assertThat(properties.backoffFor(1)).isEqualTo(expected[0]);
            // 시도 번호가 목록보다 크면 마지막 값으로 고정된다.
            assertThat(properties.backoffFor(99)).isEqualTo(expected[expected.length - 1]);
        };
    }

    @EnableConfigurationProperties(RetryProperties.class)
    static class EnableRetryProperties {}
}
