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
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.convert.DurationStyle;
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
    private final RestClient uploadClient;
    private final ObjectMapper objectMapper;

    private volatile String resolvedDriveId;

    /**
     * {@code spring.http.client.read-timeout}(10초)은 JDK 클라이언트에서 <b>바디 전송을 포함한 요청
     * 전체의 데드라인</b>이라, 그대로 두면 10 MiB 청크 PUT이 지속 8.4 Mbps 미만에서 무조건 끊긴다.
     * 청크 전송·세션 조회만 별도 타임아웃을 쓴다 — 전역 값은 health/StartupChecks가 워커 스레드를
     * 영구 점유하지 않게 하려던 것이라 그대로 둔다.
     *
     * <p>자동 구성된 {@code ClientHttpRequestFactorySettings}를 받아 read timeout만 덮어쓴다 —
     * {@code defaults()}로 새로 만들면 {@code connect-timeout: 5s}와 리다이렉트 정책이 함께 사라진다
     * (실측: {@code defaults().connectTimeout()}이 null이고 Boot의 PropertyMapper가 null은 아예 걸지
     * 않아 JDK 기본값 '무제한'이 된다). {@code ClientHttpRequestFactoryBuilder}도 주입받아
     * {@code spring.http.client.factory} 오버라이드를 그대로 따른다.
     */
    @Autowired
    public GraphApiClient(
            GraphProperties properties,
            GraphTokenService tokenService,
            RetryExecutor retryExecutor,
            ObjectMapper objectMapper,
            RestClient.Builder builder,
            ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
            ClientHttpRequestFactorySettings requestFactorySettings,
            @Value("${deployhub.upload.request-timeout:300s}") String uploadRequestTimeout) {
        // clone()은 아래 생성자가 builder에 baseUrl을 붙이기 전에 떠야 한다(Builder는 가변이다).
        this(
                properties,
                tokenService,
                retryExecutor,
                objectMapper,
                builder,
                builder.clone()
                        .requestFactory(requestFactoryBuilder.build(
                                requestFactorySettings.withReadTimeout(positiveDuration(uploadRequestTimeout)))));
    }

    /**
     * 단위 없는 값은 초로 읽는다 — {@code @Value}는 {@code @ConfigurationProperties}와 달리 Duration
     * 변환기가 붙지 않아 {@code Duration} 파라미터로 받으면 기동이 죽는다(실측: "no matching editors").
     * 0·음수면 모든 청크 PUT이 즉시 타임아웃돼 업로드가 영구 실패하므로 여기서 기동을 막는다.
     */
    private static Duration positiveDuration(String value) {
        Duration duration = DurationStyle.detectAndParse(value, ChronoUnit.SECONDS);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException("deployhub.upload.request-timeout은 0보다 커야 합니다: " + value);
        }
        return duration;
    }

    /** 테스트가 두 클라이언트를 같은 {@code MockRestServiceServer}에 물릴 수 있게 분리한 생성자. */
    GraphApiClient(
            GraphProperties properties,
            GraphTokenService tokenService,
            RetryExecutor retryExecutor,
            ObjectMapper objectMapper,
            RestClient.Builder builder,
            RestClient.Builder uploadBuilder) {
        this.properties = properties;
        this.tokenService = tokenService;
        this.retryExecutor = retryExecutor;
        this.objectMapper = objectMapper;
        // 업로드 URL은 절대 URL이라 baseUrl이 없다. 같은 builder를 넘기는 테스트를 위해 먼저 만든다 —
        // 아래 baseUrl 대입이 같은 인스턴스를 바꿔도 이미 만들어진 클라이언트에는 영향이 없다.
        this.uploadClient = uploadBuilder.build();
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
            ResponseEntity<String> response = uploadClient
                    .put()
                    .uri(parseUploadUrl(uploadUrl))
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
            return uploadClient.get().uri(parseUploadUrl(uploadUrl)).retrieve().body(String.class);
        } catch (ResourceAccessException ex) {
            throw uploadSessionTimeoutRetryable(ex);
        }
    }

    /**
     * {@code uploadUrl}은 tempauth 토큰이 담긴 사전 인증 URL이라 <b>그 자체가 자격증명</b>이다 —
     * {@link URI#create}가 던지는 {@code IllegalArgumentException}에는 입력 문자열이 통째로 들어가고
     * 그 메시지가 업로드 실패 detail로 로그에 찍힌다. 메시지 없는 예외로 바꿔 끊는다.
     */
    private static URI parseUploadUrl(String uploadUrl) {
        try {
            return URI.create(uploadUrl);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.GRAPH_UNAVAILABLE);
        }
    }

    /** 위임 인증이라 사이트가 아니라 로그인한 계정의 드라이브를 본다. */
    public void healthCheck() {
        get("/me/drive/root");
    }

    /** {@code SP_DRIVE_ID}가 없으면 로그인한 계정의 기본 드라이브를 조회해 메모리에 캐시한다. */
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
                resolvedDriveId = extractId(get("/me/drive"));
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
