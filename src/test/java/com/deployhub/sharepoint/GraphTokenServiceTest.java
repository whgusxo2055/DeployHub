package com.deployhub.sharepoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.deployhub.common.retry.RetryExecutor;
import com.deployhub.common.retry.RetryProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** client_credentials 토큰 캐시(재사용/무효화)를 검증한다. */
class GraphTokenServiceTest {

    private static final GraphProperties PROPERTIES =
            new GraphProperties("tenant", "client", "secret", "site", null, "/Deploy/Packages");

    private MockRestServiceServer server;
    private GraphTokenService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RetryExecutor retryExecutor =
                new RetryExecutor(new RetryProperties(1, List.of(Duration.ofMillis(1))), duration -> {});
        service = new GraphTokenService(PROPERTIES, retryExecutor, builder);
    }

    @Test
    void 토큰을_한번만_발급받고_이후_호출은_캐시를_재사용한다() {
        server.expect(ExpectedCount.once(), requestTo("https://login.microsoftonline.com/tenant/oauth2/v2.0/token"))
                .andRespond(withSuccess("{\"access_token\":\"tok-abc\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        String first = service.getAccessToken();
        String second = service.getAccessToken();

        assertThat(first).isEqualTo("tok-abc");
        assertThat(second).isEqualTo("tok-abc");
        server.verify();
    }

    @Test
    void invalidate_이후에는_캐시를_쓰지_않고_새로_발급받는다() {
        server.expect(requestTo("https://login.microsoftonline.com/tenant/oauth2/v2.0/token"))
                .andRespond(withSuccess("{\"access_token\":\"tok-1\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://login.microsoftonline.com/tenant/oauth2/v2.0/token"))
                .andRespond(withSuccess("{\"access_token\":\"tok-2\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        String first = service.getAccessToken();
        service.invalidate();
        String second = service.getAccessToken();

        assertThat(first).isEqualTo("tok-1");
        assertThat(second).isEqualTo("tok-2");
        server.verify();
    }
}
