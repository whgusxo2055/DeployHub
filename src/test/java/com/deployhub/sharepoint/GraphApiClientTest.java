package com.deployhub.sharepoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
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
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
        client = new GraphApiClient(PROPERTIES, tokenService, retryExecutor, new ObjectMapper(), builder, builder);
    }

    @Test
    void 응답이_401이면_토큰을_무효화하고_새_토큰으로_한번_재시도한다() {
        when(tokenService.getAccessToken()).thenReturn("stale-token", "fresh-token");
        server.expect(requestTo("https://graph.microsoft.com/v1.0/me/drive/root"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer stale-token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo("https://graph.microsoft.com/v1.0/me/drive/root"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fresh-token"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.healthCheck();

        verify(tokenService).invalidate();
        server.verify();
    }

    @Test
    void 응답이_403이면_권한_부족으로_즉시_실패한다() {
        when(tokenService.getAccessToken()).thenReturn("token");
        server.expect(requestTo("https://graph.microsoft.com/v1.0/me/drive/root"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.healthCheck())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.GRAPH_FORBIDDEN);
    }

    @Test
    void driveId_미설정이면_사이트_조회_결과를_한번만_호출해서_캐시한다() {
        when(tokenService.getAccessToken()).thenReturn("token");
        server.expect(requestTo("https://graph.microsoft.com/v1.0/me/drive"))
                .andRespond(withSuccess("{\"id\":\"drive-abc\"}", MediaType.APPLICATION_JSON));

        String first = client.resolveDriveId();
        String second = client.resolveDriveId();

        assertThat(first).isEqualTo("drive-abc");
        assertThat(second).isEqualTo("drive-abc");
        server.verify();
    }

    @Test
    void getOrNull은_404면_빈_값을_반환한다() {
        when(tokenService.getAccessToken()).thenReturn("token");
        server.expect(requestTo("https://graph.microsoft.com/v1.0/drives/d1/root:/Deploy/Packages/2026.08.05"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        Optional<String> result = client.getOrNull("/drives/d1/root:/Deploy/Packages/2026.08.05");

        assertThat(result).isEmpty();
    }

    @Test
    void getOrNull은_404가_아니면_예외를_그대로_던진다() {
        when(tokenService.getAccessToken()).thenReturn("token");
        server.expect(requestTo("https://graph.microsoft.com/v1.0/drives/d1/root:/Deploy/Packages/2026.08.05"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> client.getOrNull("/drives/d1/root:/Deploy/Packages/2026.08.05"))
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);
    }

    @Test
    void post는_인증_헤더와_JSON_바디를_보낸다() {
        when(tokenService.getAccessToken()).thenReturn("token");
        server.expect(requestTo("https://graph.microsoft.com/v1.0/drives/d1/items/parent/children"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token"))
                .andRespond(withSuccess("{\"id\":\"folder-1\"}", MediaType.APPLICATION_JSON));

        String response = client.post(
                "/drives/d1/items/parent/children", Map.of("name", "2026.08.05", "folder", Map.of()));

        assertThat(response).contains("folder-1");
    }

    @Test
    void delete는_404를_성공으로_처리한다() {
        when(tokenService.getAccessToken()).thenReturn("token");
        server.expect(requestTo("https://graph.microsoft.com/v1.0/drives/d1/items/child-1"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        client.delete("/drives/d1/items/child-1");

        server.verify();
    }

    @Test
    void putChunk는_인증_헤더_없이_ContentRange를_붙여_절대_URL로_보낸다() {
        server.expect(requestTo("https://upload.sharepoint.example.com/session/abc"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.CONTENT_RANGE, "bytes 0-9/20"))
                .andExpect(request -> assertThat(request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION))
                        .isFalse())
                .andRespond(withStatus(HttpStatus.ACCEPTED).body("{\"nextExpectedRanges\":[\"10-19\"]}"));

        GraphApiClient.ChunkUploadResult result =
                client.putChunk("https://upload.sharepoint.example.com/session/abc", new byte[10], 0, 9, 20);

        assertThat(result.statusCode()).isEqualTo(202);
        assertThat(result.success()).isTrue();
        server.verify();
    }

    @Test
    void putChunk는_오류_상태코드도_예외_없이_그대로_반환한다() {
        server.expect(requestTo("https://upload.sharepoint.example.com/session/abc"))
                .andRespond(withStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE));

        GraphApiClient.ChunkUploadResult result =
                client.putChunk("https://upload.sharepoint.example.com/session/abc", new byte[10], 0, 9, 20);

        assertThat(result.statusCode()).isEqualTo(416);
        assertThat(result.success()).isFalse();
    }
}
