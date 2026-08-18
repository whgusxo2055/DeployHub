package com.deployhub.sharepoint;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.common.retry.RetryAfterHeader;
import com.deployhub.common.retry.RetryExecutor;
import com.deployhub.common.retry.RetryableCallException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Graph 위임(delegated) 토큰 발급. 만료 5분 전 선제 갱신하는 메모리 캐시를 둔다.
 * {@link RestClient.Builder}를 주입받아야 {@code spring.http.client.*} 타임아웃이 붙는다.
 *
 * <p><b>public client 흐름이라 {@code client_secret}을 보내지 않는다</b> — device code로 최초 1회
 * 로그인해 얻은 refresh token을 파일에 두고 그것만으로 갱신한다.
 * {@code GraphProperties.clientSecret}은 회사 테넌트(app-only) 복귀를 대비해 남겨둔 미사용 값이다.
 */
@Slf4j
@Service
public class GraphTokenService {

    private static final Duration EARLY_REFRESH = Duration.ofMinutes(5);
    private static final String SCOPE = "https://graph.microsoft.com/Files.ReadWrite offline_access";

    private final GraphProperties properties;
    private final RetryExecutor retryExecutor;
    private final RestClient tokenClient;
    private final Path refreshTokenFile;
    private final Object refreshLock = new Object();

    private volatile CachedToken cached;
    private volatile String refreshToken;

    public GraphTokenService(
            GraphProperties properties,
            RetryExecutor retryExecutor,
            RestClient.Builder builder,
            @Value("${deployhub.sharepoint.refresh-token-file}") String refreshTokenFile) {
        this.properties = properties;
        this.retryExecutor = retryExecutor;
        this.tokenClient =
                builder.baseUrl("https://login.microsoftonline.com/" + properties.tenantId()).build();
        // 절대경로로 정규화한다 — 상대 파일명만 주면 getParent()가 null이라 저장 시 NPE가 난다.
        this.refreshTokenFile = Path.of(refreshTokenFile).toAbsolutePath().normalize();
    }

    public String getAccessToken() {
        CachedToken current = cached;
        if (current != null && current.isValid()) {
            return current.token();
        }
        // ponytail: 락 안에서 재시도 백오프까지 블로킹으로 돈다 — 발급이 실패하는 동안
        // 모든 Graph 호출자가 이 락에서 같이 대기한다. 대량 업로드가 병목이 되면 분리할 것.
        synchronized (refreshLock) {
            current = cached;
            if (current != null && current.isValid()) {
                return current.token();
            }
            CachedToken fresh = retryExecutor.execute("Graph 토큰 발급", this::requestToken);
            cached = fresh;
            return fresh.token();
        }
    }

    /** 401로 거부된 캐시 토큰을 무효화한다 — 다음 호출이 새로 발급받는다. */
    public void invalidate() {
        cached = null;
    }

    private CachedToken requestToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", properties.clientId());
        form.add("refresh_token", readRefreshToken());
        form.add("scope", SCOPE);

        try {
            TokenResponse response = tokenClient
                    .post()
                    .uri("/oauth2/v2.0/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null) {
                log.warn("Graph 토큰 응답이 비어 있습니다.");
                throw new ApiException(ErrorCode.GRAPH_TOKEN_ISSUE_FAILED);
            }
            // Entra는 갱신마다 새 refresh token을 주고 기존 것을 폐기한다(rotation) —
            // 이걸 저장하지 않으면 다음 갱신에서 죽는다.
            storeRefreshToken(response.refreshToken());
            return new CachedToken(response.accessToken(), Instant.now().plusSeconds(response.expiresIn()));
        } catch (RestClientResponseException ex) {
            throw classify(ex);
        } catch (ResourceAccessException ex) {
            // ex.getMessage()에 요청 URL(테넌트 ID 포함)이 들어 있다 — 로그에만 남기고
            // 예외 메시지는 기본값으로 둬 무인증 호출자에게 새어 나가지 않게 한다.
            log.warn("Graph 토큰 발급 시간 초과: {}", ex.getMessage());
            throw new RetryableCallException(new ApiException(ErrorCode.GRAPH_TOKEN_ISSUE_FAILED));
        }
    }

    private RuntimeException classify(RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        log.warn("Graph 토큰 발급 실패({})", status);
        // Entra ID는 잘못된 client_secret/tenant를 보통 400(invalid_client)으로 준다.
        if (status == 400 || status == 401) {
            return new ApiException(ErrorCode.GRAPH_TOKEN_ISSUE_FAILED);
        }
        if (status == 429 || status >= 500) {
            return new RetryableCallException(
                    new ApiException(ErrorCode.GRAPH_UNAVAILABLE), RetryAfterHeader.parseSeconds(ex.getResponseHeaders()));
        }
        return new ApiException(ErrorCode.GRAPH_TOKEN_ISSUE_FAILED);
    }

    /** 파일이 없으면 최초 device code 로그인이 안 된 것 — 재시도해도 안 되니 즉시 실패시킨다. */
    private String readRefreshToken() {
        // 파일 저장이 실패한 뒤에도 이 프로세스는 계속 돌아야 한다 — 메모리 값이 항상 최신이다.
        String inMemory = refreshToken;
        if (inMemory != null) {
            return inMemory;
        }
        try {
            String token = Files.readString(refreshTokenFile).strip();
            if (token.isEmpty()) {
                throw new IOException("파일이 비어 있습니다.");
            }
            return token;
        } catch (IOException ex) {
            // 경로만 남긴다 — 토큰 값은 어떤 경우에도 로그에 싣지 않는다.
            log.error("Graph refresh token을 읽을 수 없습니다: {} ({})", refreshTokenFile, ex.getMessage());
            throw new ApiException(ErrorCode.GRAPH_TOKEN_ISSUE_FAILED);
        }
    }

    /**
     * 임시 파일에 쓴 뒤 원자적으로 옮긴다 — 반쪽 파일을 남기지 않는다.
     *
     * <p><b>쓰기 실패에 예외를 던지지 않는다</b> — 여기서 던지면 방금 정상 발급받은 액세스 토큰까지
     * 버려 Job이 즉시 죽는다. 새 값을 메모리에 붙들어 이 프로세스는 계속 돌리고, 재시작 시
     * 재로그인이 필요할 수 있다는 사실만 ERROR로 남긴다.
     * (문서상 옛 refresh token이 즉시 폐기되지는 않으므로 파일의 값으로 살아날 여지도 있다.)
     *
     * <p>ponytail: 토큰 응답이 읽기 타임아웃으로 끊기면 Entra 쪽에서 이미 회전됐는데도 재시도가
     * 폐기된 토큰을 보낸다 — MSA refresh token이 1회용이라 클라이언트로는 못 막는다.
     */
    private void storeRefreshToken(String token) {
        if (token == null || token.isBlank()) {
            log.warn("Graph 토큰 응답에 refresh_token이 없습니다 — 기존 값을 유지합니다.");
            return;
        }
        refreshToken = token;
        Path tmp = refreshTokenFile.resolveSibling(refreshTokenFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(refreshTokenFile.getParent());
            // 남아 있던 tmp를 재사용하면 그 파일의 느슨한 권한을 그대로 물려받는다(심링크면 따라간다).
            Files.deleteIfExists(tmp);
            createOwnerOnly(tmp);
            Files.writeString(tmp, token);
            Files.move(tmp, refreshTokenFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            log.error(
                    "Graph refresh token 저장에 실패했습니다 — 재시작 시 device code 재로그인이 필요합니다: {} ({})",
                    refreshTokenFile,
                    ex.getMessage());
        }
    }

    /**
     * refresh token은 계정 전체를 여는 장기 자격증명이다 — <b>생성 시점에</b> 소유자 외 접근을 막는다.
     * 만들고 나서 좁히면 그 사이 크래시 시 world-readable 파일이 토큰을 담은 채 영구히 남는다.
     */
    private void createOwnerOnly(Path path) throws IOException {
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException ex) {
            Files.createFile(path);
            log.warn("POSIX 권한을 지원하지 않는 파일시스템입니다 — refresh token 파일 권한을 직접 확인하세요: {}", path);
        }
    }

    /** 장기 자격증명을 담으므로 {@code GraphProperties}와 같이 {@code toString()}을 마스킹한다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") long expiresIn) {

        @Override
        public String toString() {
            return "TokenResponse[expiresIn=%d, accessToken=****, refreshToken=****]".formatted(expiresIn);
        }
    }

    private record CachedToken(String token, Instant expiresAt) {
        boolean isValid() {
            return Instant.now().isBefore(expiresAt.minus(EARLY_REFRESH));
        }
    }
}
