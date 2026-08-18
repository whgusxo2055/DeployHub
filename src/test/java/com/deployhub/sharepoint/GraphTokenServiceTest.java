package com.deployhub.sharepoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.common.retry.RetryExecutor;
import com.deployhub.common.retry.RetryProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 위임 토큰 캐시(재사용/무효화)와 refresh token rotation 저장을 검증한다. */
class GraphTokenServiceTest {

    private static final String TOKEN_URL = "https://login.microsoftonline.com/tenant/oauth2/v2.0/token";
    private static final GraphProperties PROPERTIES =
            new GraphProperties("tenant", "client", "secret", "site", null, "/Deploy/Packages");

    @TempDir
    Path tempDir;

    private Path refreshTokenFile;
    private MockRestServiceServer server;
    private GraphTokenService service;

    @BeforeEach
    void setUp() throws IOException {
        refreshTokenFile = tempDir.resolve(".graph-refresh-token");
        Files.writeString(refreshTokenFile, "rt-initial");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RetryExecutor retryExecutor =
                new RetryExecutor(new RetryProperties(1, List.of(Duration.ofMillis(1))), duration -> {});
        service = new GraphTokenService(PROPERTIES, retryExecutor, builder, refreshTokenFile.toString());
    }

    @Test
    void 토큰을_한번만_발급받고_이후_호출은_캐시를_재사용한다() {
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URL))
                .andRespond(withSuccess(
                        "{\"access_token\":\"tok-abc\",\"refresh_token\":\"rt-next\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        String first = service.getAccessToken();
        String second = service.getAccessToken();

        assertThat(first).isEqualTo("tok-abc");
        assertThat(second).isEqualTo("tok-abc");
        server.verify();
    }

    @Test
    void invalidate_이후에는_캐시를_쓰지_않고_새로_발급받는다() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess(
                        "{\"access_token\":\"tok-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess(
                        "{\"access_token\":\"tok-2\",\"refresh_token\":\"rt-2\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        String first = service.getAccessToken();
        service.invalidate();
        String second = service.getAccessToken();

        assertThat(first).isEqualTo("tok-1");
        assertThat(second).isEqualTo("tok-2");
        server.verify();
    }

    @Test
    void 파일의_refresh_token으로_갱신을_요청한다() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(content().string(containsString("grant_type=refresh_token")))
                .andExpect(content().string(containsString("refresh_token=rt-initial")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"tok\",\"refresh_token\":\"rt-next\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        service.getAccessToken();

        server.verify();
    }

    /**
     * rotation을 저장하지 않으면 다음 갱신이 폐기된 토큰을 보내 죽는다 —
     * {@code storeRefreshToken} 호출을 지우면 이 테스트가 실패해야 한다.
     */
    @Test
    void 응답의_새_refresh_token을_파일에_덮어쓴다() throws IOException {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess(
                        "{\"access_token\":\"tok\",\"refresh_token\":\"rt-rotated\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        service.getAccessToken();

        assertThat(Files.readString(refreshTokenFile)).isEqualTo("rt-rotated");
    }

    @Test
    void 응답에_refresh_token이_없으면_기존_파일을_보존한다() throws IOException {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"tok\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        service.getAccessToken();

        assertThat(Files.readString(refreshTokenFile)).isEqualTo("rt-initial");
    }

    /** 운영자가 가장 먼저 밟는 분기 — device code 최초 로그인 전에는 파일이 없다. */
    @Test
    void refresh_token_파일이_없으면_토큰_발급에_실패한다() throws IOException {
        Files.delete(refreshTokenFile);

        assertThatThrownBy(() -> service.getAccessToken())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.GRAPH_TOKEN_ISSUE_FAILED);
    }

    @Test
    void refresh_token_파일이_비어_있으면_토큰_발급에_실패한다() throws IOException {
        Files.writeString(refreshTokenFile, "   \n");

        assertThatThrownBy(() -> service.getAccessToken())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.GRAPH_TOKEN_ISSUE_FAILED);
    }

    /**
     * 저장 실패 시점엔 Entra가 이미 옛 토큰을 폐기한 뒤라, 예외를 던지면 방금 받은 액세스 토큰까지
     * 버려 Job이 즉시 죽는다. 파일 쓰기는 best-effort여야 한다 —
     * {@code storeRefreshToken}의 catch가 예외를 던지도록 되돌리면 이 테스트가 실패한다.
     */
    @Test
    void refresh_token_저장에_실패해도_발급받은_액세스_토큰을_반환한다() throws IOException {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "root는 권한 검사를 우회한다");
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(tempDir);
        Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("r-x------"));
        try {
            server.expect(requestTo(TOKEN_URL))
                    .andRespond(withSuccess(
                            "{\"access_token\":\"tok\",\"refresh_token\":\"rt-rotated\",\"expires_in\":3600}",
                            MediaType.APPLICATION_JSON));

            assertThat(service.getAccessToken()).isEqualTo("tok");
        } finally {
            Files.setPosixFilePermissions(tempDir, original);
        }
    }

    /**
     * 생성 시점부터 0600이어야 한다 — 만들고 나서 좁히면 그 사이 크래시 시 world-readable 파일에
     * 토큰이 남는다. 최종 상태는 수정 전에도 0600이라 회귀 테스트는 아니고 불변식 고정용이다.
     */
    @Test
    void 저장된_refresh_token_파일은_소유자만_읽을_수_있다() throws IOException {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess(
                        "{\"access_token\":\"tok\",\"refresh_token\":\"rt-rotated\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        service.getAccessToken();

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(refreshTokenFile);
        assertThat(perms).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(Files.exists(refreshTokenFile.resolveSibling(refreshTokenFile.getFileName() + ".tmp")))
                .isFalse();
    }
}
