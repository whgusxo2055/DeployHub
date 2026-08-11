package com.deployhub.sharepoint;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.common.retry.RetryAfterHeader;
import com.deployhub.common.retry.RetryExecutor;
import com.deployhub.common.retry.RetryableCallException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Graph 클라이언트 자격 증명(App-only) 토큰 발급. 만료 5분 전 선제 갱신하는 메모리 캐시를 둔다.
 * {@link RestClient.Builder}를 주입받아야 {@code spring.http.client.*} 타임아웃이 붙는다.
 */
@Slf4j
@Service
public class GraphTokenService {

    private static final Duration EARLY_REFRESH = Duration.ofMinutes(5);
    private static final String SCOPE = "https://graph.microsoft.com/.default";

    private final GraphProperties properties;
    private final RetryExecutor retryExecutor;
    private final RestClient tokenClient;
    private final Object refreshLock = new Object();

    private volatile CachedToken cached;

    public GraphTokenService(GraphProperties properties, RetryExecutor retryExecutor, RestClient.Builder builder) {
        this.properties = properties;
        this.retryExecutor = retryExecutor;
        this.tokenClient =
                builder.baseUrl("https://login.microsoftonline.com/" + properties.tenantId()).build();
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
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken, @JsonProperty("expires_in") long expiresIn) {}

    private record CachedToken(String token, Instant expiresAt) {
        boolean isValid() {
            return Instant.now().isBefore(expiresAt.minus(EARLY_REFRESH));
        }
    }
}
