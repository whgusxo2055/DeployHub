package com.deployhub.sharepoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.common.retry.RetryExecutor;
import com.deployhub.common.retry.RetryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GraphApiClientTest {

    private static final GraphProperties PROPERTIES =
            new GraphProperties("tenant", "client", "secret", "site-1", null, "/Deploy/Packages");

    private MockRestServiceServer server;
    private GraphTokenService tokenService;
    private GraphApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tokenService = mock(GraphTokenService.class);
        RetryExecutor retryExecutor =
                new RetryExecutor(new RetryProperties(1, List.of(Duration.ofMillis(1))), duration -> {});
        client = new GraphApiClient(PROPERTIES, tokenService, retryExecutor, new ObjectMapper(), builder);
    }

    @Test
    void 응답이_401이면_토큰을_무효화하고_새_토큰으로_한번_재시도한다() {
        when(tokenService.getAccessToken()).thenReturn("stale-token", "fresh-token");
        server.expect(requestTo("https://graph.microsoft.com/v1.0/sites/site-1/drive/root"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stale-token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo("https://graph.microsoft.com/v1.0/sites/site-1/drive/root"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh-token"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.healthCheck();

        verify(tokenService).invalidate();
        server.verify();
    }

    @Test
    void 응답이_403이면_권한_부족으로_즉시_실패한다() {
        when(tokenService.getAccessToken()).thenReturn("token");
        server.expect(requestTo("https://graph.microsoft.com/v1.0/sites/site-1/drive/root"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.healthCheck())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.GRAPH_FORBIDDEN);
    }

    @Test
    void driveId_미설정이면_사이트_조회_결과를_한번만_호출해서_캐시한다() {
        when(tokenService.getAccessToken()).thenReturn("token");
        server.expect(requestTo("https://graph.microsoft.com/v1.0/sites/site-1/drive"))
                .andRespond(withSuccess("{\"id\":\"drive-abc\"}", MediaType.APPLICATION_JSON));

        String first = client.resolveDriveId();
        String second = client.resolveDriveId();

        assertThat(first).isEqualTo("drive-abc");
        assertThat(second).isEqualTo("drive-abc");
        server.verify();
    }
}
