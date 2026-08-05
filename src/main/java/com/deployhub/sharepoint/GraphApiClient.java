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
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 구현계획서 Phase 2 작업 항목 2 — FN-04-5 Microsoft Graph 인증된 호출과 드라이브 조회.
 * Phase 5(FN-08 폴더 확보, FN-09 업로드)가 POST/DELETE·청크 PUT을 추가해 재사용한다.
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

    /**
     * FN-08 폴더 존재 확인(구현계획서 523-527행) — 404는 예외가 아니라 {@link Optional#empty()}로
     * 돌려준다. {@code NcrRegistryClient.getManifest}와 동일한 패턴이다.
     */
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
        return retryExecutor.execute("Graph POST " + path, () -> attemptPost(path, body));
    }

    /**
     * FN-08 폴더 재사용 시 기존 파일 정리(구현계획서 533행). 이미 지워진 항목(404)은
     * 목표를 달성한 것으로 보고 성공 취급한다.
     */
    public void delete(String path) {
        try {
            retryExecutor.execute("Graph DELETE " + path, () -> {
                attemptDelete(path);
                return Boolean.TRUE;
            });
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 404) {
                throw ex;
            }
        }
    }

    /**
     * FN-09 청크 업로드(구현계획서 549-557행). {@code uploadUrl}은 사전 인증된 절대 URL이라
     * {@code Authorization} 헤더를 실으면 401이 난다(555행) — 그래서 이 메서드만 Bearer 토큰을
     * 붙이지 않고, {@link RelativePathGuard}도 적용하지 않는다(절대 URL이 의도된 입력이다).
     * 오류 상태 코드도 예외로 던지지 않고 그대로 반환한다 — 416(E-1103)·404/410(E-1102) 등을
     * 호출자({@code GraphUploadService})가 세분해서 처리해야 하기 때문이다.
     *
     * <p>{@code .uri(URI)}로 넘긴다 — {@code .uri(String)}은 값을 URI *템플릿*으로 취급해
     * {@code uploadUrl}에 이미 들어있는 퍼센트 인코딩을 다시 인코딩하거나(이중 인코딩),
     * 쿼리에 우연히 `{}`가 섞여 있으면 변수 확장을 시도하다 예외를 던진다. {@link URI#create}는
     * 이미 완성된 URL을 그대로 쓴다.
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
            // 429(E-1106) — 서버가 명시한 대기 시간을 호출자(GraphUploadService)가 로컬
            // 백오프보다 우선하도록 함께 돌려준다.
            Duration retryAfter = RetryAfterHeader.parseSeconds(ex.getResponseHeaders());
            return new ChunkUploadResult(ex.getStatusCode().value(), ex.getResponseBodyAsString(), retryAfter);
        } catch (ResourceAccessException ex) {
            throw uploadSessionTimeoutRetryable(ex);
        }
    }

    /** 업로드 세션 상태 조회(구현계획서 558행, 중단 후 재개용). 인증 헤더 미부착·URI 처리는 {@link #putChunk}와 동일한 이유. */
    public String getUploadSessionStatus(String uploadUrl) {
        try {
            return restClient.get().uri(URI.create(uploadUrl)).retrieve().body(String.class);
        } catch (ResourceAccessException ex) {
            throw uploadSessionTimeoutRetryable(ex);
        }
    }

    /** Job 취소 시 세션 정리(구현계획서 560행). 이미 소멸한 세션(404/410)은 성공 취급한다. */
    public void deleteUploadSession(String uploadUrl) {
        try {
            restClient.delete().uri(URI.create(uploadUrl)).retrieve().toBodilessEntity();
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status != 404 && status != 410) {
                throw ex;
            }
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

    private String attemptPost(String path, Object body) {
        RelativePathGuard.requireRelative(path);
        try {
            return doPost(path, body);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                log.warn("Graph 인증 실패, 토큰을 무효화하고 한 번 재시도합니다.");
                tokenService.invalidate();
                return doPostClassified(path, body);
            }
            throw classify(ex, path);
        } catch (ResourceAccessException ex) {
            throw timeoutRetryable(path, ex);
        }
    }

    private String doPostClassified(String path, Object body) {
        try {
            return doPost(path, body);
        } catch (RestClientResponseException ex) {
            throw classify(ex, path);
        } catch (ResourceAccessException ex) {
            throw timeoutRetryable(path, ex);
        }
    }

    private String doPost(String path, Object body) {
        return restClient
                .post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    private void attemptDelete(String path) {
        RelativePathGuard.requireRelative(path);
        try {
            doDelete(path);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                log.warn("Graph 인증 실패, 토큰을 무효화하고 한 번 재시도합니다.");
                tokenService.invalidate();
                doDeleteClassified(path);
                return;
            }
            throw classify(ex, path);
        } catch (ResourceAccessException ex) {
            throw timeoutRetryable(path, ex);
        }
    }

    private void doDeleteClassified(String path) {
        try {
            doDelete(path);
        } catch (RestClientResponseException ex) {
            throw classify(ex, path);
        } catch (ResourceAccessException ex) {
            throw timeoutRetryable(path, ex);
        }
    }

    private void doDelete(String path) {
        restClient
                .delete()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
                .retrieve()
                .toBodilessEntity();
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
        // 404/409 등은 이 클라이언트의 책임 밖이다 — Phase 5 호출자가 폴더 재사용(404)·
        // 생성 경합(409) 같은 정상 흐름으로 다시 분류한다.
        return ex;
    }

    private RetryableCallException timeoutRetryable(String path, ResourceAccessException ex) {
        log.warn("Graph 호출 시간 초과: {} ({})", path, ex.getMessage());
        return new RetryableCallException(new ApiException(ErrorCode.GRAPH_UNAVAILABLE));
    }

    /**
     * {@code uploadUrl}은 {@code tempauth} 토큰이 담긴 사전 인증 URL이다 — 그 자체가
     * 베어러 자격증명과 동등하다. {@link #timeoutRetryable}과 달리 URL도, Spring이 예외
     * 메시지에 실어 보내는 요청 URI({@code ex.getMessage()})도 로그에 남기지 않는다.
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
