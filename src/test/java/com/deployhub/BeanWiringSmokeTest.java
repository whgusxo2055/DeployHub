package com.deployhub;

import static org.assertj.core.api.Assertions.assertThat;

import com.deployhub.common.retry.RetryExecutor;
import com.deployhub.common.retry.RetryProperties;
import com.deployhub.registry.NcrProperties;
import com.deployhub.registry.NcrRegistryClient;
import com.deployhub.sharepoint.GraphApiClient;
import com.deployhub.sharepoint.GraphProperties;
import com.deployhub.sharepoint.GraphTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

/**
 * 생성자가 2개 이상인 컴포넌트(테스트용 보조 생성자를 둔 클래스들)에서 {@code @Autowired}를
 * 빠뜨리면 "No default constructor found"로 기동이 죽는다 — 단위 테스트는 이 클래스를 직접
 * {@code new}로 생성하므로 이 실패를 잡지 못한다. 실제로 겪은 문제라 컨텍스트를 띄워
 * Spring의 생성자 선택 자체를 검증한다. DB·Flyway·네트워크 없이도 뜨도록 필요한 빈만 수동
 * 등록한다 (전체 애플리케이션 기동은 실제 NCR/Graph 자격 증명이 있어야 하므로 여기서 하지 않는다
 * — {@code dev} 프로필의 더미 값으로 전체 컨텍스트를 띄우는
 * {@link com.deployhub.version.MainVersionApiFlowIntegrationTest}가 Docker가 있는
 * 환경에서 이 빈 와이어링을 사실상 포함해서 검증한다. 이 클래스는 Docker 없이도 도는
 * 유일한 기동 검증이라 별도로 유지한다).
 */
class BeanWiringSmokeTest {

    @Test
    void 재시도_레지스트리_그래프_클라이언트가_모호한_생성자_없이_기동한다() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(
                    TestConfig.class,
                    RetryExecutor.class,
                    NcrRegistryClient.class,
                    GraphTokenService.class,
                    GraphApiClient.class);
            context.refresh();

            assertThat(context.getBean(RetryExecutor.class)).isNotNull();
            assertThat(context.getBean(NcrRegistryClient.class)).isNotNull();
            assertThat(context.getBean(GraphTokenService.class)).isNotNull();
            assertThat(context.getBean(GraphApiClient.class)).isNotNull();
        }
    }

    @Configuration
    static class TestConfig {

        @Bean
        RetryProperties retryProperties() {
            return new RetryProperties(3, List.of(Duration.ofSeconds(1)));
        }

        @Bean
        NcrProperties ncrProperties() {
            return new NcrProperties("ncr.example.com", "AK", "SK", "/usr/bin/skopeo");
        }

        @Bean
        GraphProperties graphProperties() {
            return new GraphProperties("tenant", "client", "secret", "site", null, "/Deploy/Packages");
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        // HttpClientAutoConfiguration이 주는 두 빈. GraphApiClient가 업로드 전용 클라이언트를
        // 만들 때 전역 connect-timeout·리다이렉트 정책을 상속받으려고 주입받는다.
        @Bean
        ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder() {
            return ClientHttpRequestFactoryBuilder.detect();
        }

        @Bean
        ClientHttpRequestFactorySettings clientHttpRequestFactorySettings() {
            return ClientHttpRequestFactorySettings.defaults().withConnectTimeout(Duration.ofSeconds(5));
        }

        // 실제 Boot 앱에서는 RestClientAutoConfiguration이 주입 지점마다 새 Builder를 주는
        // prototype 빈을 제공한다(spring.http.client.* 타임아웃이 여기 실린다). 이 테스트는
        // 그 자동 구성이 없으므로 동일하게 prototype으로 흉내낸다 — 세 클라이언트가 baseUrl을
        // 서로 다르게 설정하므로 빌더를 공유하면 안 된다.
        @Bean
        @Scope("prototype")
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }
}
