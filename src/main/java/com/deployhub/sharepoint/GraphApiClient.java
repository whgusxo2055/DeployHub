package com.deployhub.sharepoint;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.common.RelativePathGuard;
import com.deployhub.common.retry.RetryAfterHeader;
import com.deployhub.common.retry.RetryExecutor;
import com.deployhub.common.retry.RetryableCallException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 구현계획서 Phase 2 작업 항목 2 — FN-04-5 Microsoft Graph 인증된 호출과 드라이브 조회.
 * 파일 업로드·폴더 생성 등 실제 SharePoint 연동은 Phase 5에서 이 클라이언트를 재사용해 구현한다.
 *
 * <p>생성자로 {@link RestClient.Builder}를 주입받는다 — Spring Boot 자동 구성 빈을 쓰면
 * {@code spring.http.client.*} 타임아웃이 적용된다.
 */
@Slf4j
@Component
public class GraphApiClient {

    private final GraphProperties properties;
    private final GraphTokenService tokenService;
    private final RetryExecutor retryExecutor;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    private volatile String resolvedDriveId;

    public GraphApiClient(
            GraphProperties properties,
            GraphTokenService tokenService,
            RetryExecutor retryExecutor,
            ObjectMapper objectMapper,
            RestClient.Builder builder) {
        this.properties = properties;
        this.tokenService = tokenService;
        this.retryExecutor = retryExecutor;
        this.objectMapper = objectMapper;
        this.restClient = builder.baseUrl("https://graph.microsoft.com/v1.0").build();
    }

    public String get(String path) {
        return retryExecutor.execute("Graph GET " + path, () -> attemptGet(path));
    }

    public void healthCheck() {
        get("/sites/%s/drive/root".formatted(properties.siteId()));
    }

    /** {@code SP_DRIVE_ID}가 없으면 사이트의 기본 문서 라이브러리를 조회해 메모리에 캐시한다. */
    public String resolveDriveId() {
        if (properties.driveId() != null && !properties.driveId().isBlank()) {
            return properties.driveId();
        }
        String current = resolvedDriveId;
        if (current != null) {
            return current;
        }
        // ponytail: 락 안에서 네트워크 호출(재시도 백오프 포함)을 한다 — GraphTokenService와
        // 같은 한계다. 드라이브 조회는 프로세스 생애주기에 한 번만 일어나므로 감당 가능하다.
        synchronized (this) {
            if (resolvedDriveId == null) {
                String body = get("/sites/%s/drive".formatted(properties.siteId()));
                resolvedDriveId = extractId(body);
            }
            return resolvedDriveId;
        }
    }

    private String extractId(String json) {
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            log.warn("Graph drive 응답 파싱에 실패했습니다.", ex);
            throw new ApiException(ErrorCode.GRAPH_UNAVAILABLE);
        }
        String id = node.path("id").asText(null);
        if (id == null || id.isBlank()) {
            log.warn("Graph drive 응답에 id가 없습니다.");
            throw new ApiException(ErrorCode.GRAPH_UNAVAILABLE);
        }
        return id;
    }

    private String attemptGet(String path) {
        RelativePathGuard.requireRelative(path);
        try {
            return doGet(path);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                // 캐시된 토큰이 조기 폐기(시크릿 롤오버 등)됐을 수 있다 — 무효화하고 새
                // 토큰으로 한 번만 재시도한다. 그래도 401이면 진짜 인증 실패다.
                log.warn("Graph 인증 실패, 토큰을 무효화하고 한 번 재시도합니다.");
                tokenService.invalidate();
                return doGetClassified(path);
            }
            throw classify(ex, path);
        } catch (ResourceAccessException ex) {
            throw timeoutRetryable(path, ex);
        }
    }

    private String doGetClassified(String path) {
        try {
            return doGet(path);
        } catch (RestClientResponseException ex) {
            throw classify(ex, path);
        } catch (ResourceAccessException ex) {
            throw timeoutRetryable(path, ex);
        }
    }

    private String doGet(String path) {
        return restClient
                .get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
                .retrieve()
                .body(String.class);
    }

    private RuntimeException classify(RestClientResponseException ex, String path) {
        int status = ex.getStatusCode().value();
        log.warn("Graph 호출 실패({}): {}", status, path);
        if (status == 403) {
            return new ApiException(ErrorCode.GRAPH_FORBIDDEN);
        }
        if (status == 401) {
            return new ApiException(ErrorCode.GRAPH_TOKEN_ISSUE_FAILED);
        }
        if (status == 429 || status >= 500) {
            return new RetryableCallException(
                    new ApiException(ErrorCode.GRAPH_UNAVAILABLE), RetryAfterHeader.parseSeconds(ex.getResponseHeaders()));
        }
        // 404 등은 이 Phase의 책임 밖이다 (Phase 5가 폴더/파일 오류 코드로 다시 분류한다).
        return ex;
    }

    private RetryableCallException timeoutRetryable(String path, ResourceAccessException ex) {
        log.warn("Graph 호출 시간 초과: {} ({})", path, ex.getMessage());
        return new RetryableCallException(new ApiException(ErrorCode.GRAPH_UNAVAILABLE));
    }
}
