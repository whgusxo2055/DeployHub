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
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Microsoft Graph 인증 호출. {@link RestClient.Builder}를 주입받아야
 * {@code spring.http.client.*} 타임아웃이 붙는다(정적 create()는 무제한).
 */
@Slf4j
@Service
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
        return retryExecutor.execute("Graph GET " + path, () -> authenticated(path, token -> doGet(path, token)));
    }

    /** 404만 {@link Optional#empty()}로 돌려준다({@code NcrRegistryClient.getManifest}와 같은 패턴). */
    public Optional<String> getOrNull(String path) {
        try {
            return Optional.of(get(path));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    public String post(String path, Object body) {
        return retryExecutor.execute(
                "Graph POST " + path, () -> authenticated(path, token -> doPost(path, body, token)));
    }

    /** 이미 지워진 항목(404)은 목표를 달성한 것으로 보고 성공 취급한다. */
    public void delete(String path) {
        try {
            retryExecutor.execute("Graph DELETE " + path, () -> authenticated(path, token -> doDelete(path, token)));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 404) {
                throw ex;
            }
        }
    }

    /**
     * 청크 업로드. {@code uploadUrl}은 사전 인증된 절대 URL이라 {@code Authorization}을 실으면 401이다 —
     * 이 메서드만 Bearer도 {@link RelativePathGuard}도 적용하지 않는다. 오류 상태 코드는 호출자가
     * 세분해야 해서 예외로 던지지 않고 그대로 반환한다.
     * {@code .uri(String)}이 아니라 {@link URI#create} — 문자열은 URI 템플릿으로 취급돼 이중 인코딩된다.
     */
    public ChunkUploadResult putChunk(String uploadUrl, byte[] chunk, long rangeStart, long rangeEnd, long totalSize) {
        try {
            ResponseEntity<String> response = restClient
                    .put()
                    .uri(URI.create(uploadUrl))
                    .header(HttpHeaders.CONTENT_RANGE, "bytes %d-%d/%d".formatted(rangeStart, rangeEnd, totalSize))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(chunk)
                    .retrieve()
                    .toEntity(String.class);
            return new ChunkUploadResult(response.getStatusCode().value(), response.getBody(), null);
        } catch (RestClientResponseException ex) {
            // 429일 때 서버가 명시한 대기 시간을 호출자가 로컬 백오프보다 우선하도록 함께 돌려준다.
            Duration retryAfter = RetryAfterHeader.parseSeconds(ex.getResponseHeaders());
            return new ChunkUploadResult(ex.getStatusCode().value(), ex.getResponseBodyAsString(), retryAfter);
        } catch (ResourceAccessException ex) {
            throw uploadSessionTimeoutRetryable(ex);
        }
    }

    /** 중단 후 재개용 세션 상태 조회. 인증 헤더 미부착·URI 처리는 {@link #putChunk}와 같은 이유. */
    public String getUploadSessionStatus(String uploadUrl) {
        try {
            return restClient.get().uri(URI.create(uploadUrl)).retrieve().body(String.class);
        } catch (ResourceAccessException ex) {
            throw uploadSessionTimeoutRetryable(ex);
        }
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
        // ponytail: 락 안에서 네트워크 호출을 한다 — 드라이브 조회는 프로세스당 한 번이라 감당 가능하다.
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

    /**
     * 상대 경로 검사 후 Bearer 토큰으로 호출한다. 401이면 캐시 토큰이 조기 폐기된 것으로
     * 보고(시크릿 롤오버 등) 무효화 후 <b>정확히 한 번</b> 재시도한다 — 두 번째 401은 진짜 인증 실패다.
     */
    private <T> T authenticated(String path, Function<String, T> call) {
        RelativePathGuard.requireRelative(path);
        try {
            return call.apply(tokenService.getAccessToken());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 401) {
                throw classify(ex, path);
            }
            log.warn("Graph 인증 실패, 토큰을 무효화하고 한 번 재시도합니다.");
            tokenService.invalidate();
        } catch (ResourceAccessException ex) {
            throw timeoutRetryable(path, ex);
        }
        try {
            return call.apply(tokenService.getAccessToken());
        } catch (RestClientResponseException ex) {
            throw classify(ex, path);
        } catch (ResourceAccessException ex) {
            throw timeoutRetryable(path, ex);
        }
    }

    private String doGet(String path, String token) {
        return restClient
                .get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(String.class);
    }

    private String doPost(String path, Object body, String token) {
        return restClient
                .post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    private Boolean doDelete(String path, String token) {
        restClient
                .delete()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .toBodilessEntity();
        return Boolean.TRUE;
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
        return ex; // 404/409 등은 호출자가 폴더 재사용·생성 경합 같은 정상 흐름으로 다시 분류한다.
    }

    private RetryableCallException timeoutRetryable(String path, ResourceAccessException ex) {
        log.warn("Graph 호출 시간 초과: {} ({})", path, ex.getMessage());
        return new RetryableCallException(new ApiException(ErrorCode.GRAPH_UNAVAILABLE));
    }

    /**
     * {@code uploadUrl}은 tempauth 토큰이 담긴 사전 인증 URL이라 그 자체가 베어러 자격증명이다 —
     * URL도, 예외 메시지에 실린 요청 URI도 로그에 남기지 않는다.
     */
    private RetryableCallException uploadSessionTimeoutRetryable(ResourceAccessException ex) {
        log.warn("Graph 업로드 세션 호출 시간 초과: {}", ex.getClass().getSimpleName());
        return new RetryableCallException(new ApiException(ErrorCode.GRAPH_UNAVAILABLE));
    }

    /** 청크 PUT 응답 — 2xx는 진행/완료, 4xx/5xx는 호출자가 상태 코드로 분기한다. */
    public record ChunkUploadResult(int statusCode, String body, Duration retryAfter) {
        public boolean success() {
            return statusCode == 200 || statusCode == 201 || statusCode == 202;
        }
    }
}
