package com.deployhub.registry;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.common.RelativePathGuard;
import com.deployhub.common.retry.RetryAfterHeader;
import com.deployhub.common.retry.RetryExecutor;
import com.deployhub.common.retry.RetryableCallException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 구현계획서 Phase 2 작업 항목 1 — FN-04-1 NCP Container Registry.
 *
 * <p>REST 경로는 Basic 인증을 먼저 시도하고, {@code 401 + WWW-Authenticate: Bearer}
 * 응답을 받으면 해당 realm에서 토큰을 발급받아 Bearer로 전환한다(Docker Registry v2 토큰
 * 인증 흐름). CLI 경로(skopeo)는 {@link #credentialsForCli()}로 {@code --src-creds} 값을
 * 제공한다. 실제 다운로드는 Phase 4에서 구현한다.
 *
 * <p>생성자로 {@link RestClient.Builder}를 주입받는다 — Spring Boot가 자동 구성하는 빈을
 * 쓰면 {@code spring.http.client.*} 타임아웃이 그대로 적용된다(정적 {@code RestClient.create()}를
 * 쓰면 타임아웃이 무제한이 된다).
 */
@Slf4j
@Component
public class NcrRegistryClient {

    private static final Pattern PARAM = Pattern.compile("^(\\w+)\\s*=\\s*\"([^\"]*)\"$");
    private static final Pattern BEARER_SCHEME = Pattern.compile("(?i)^bearer\\b\\s*(.*)$");
    // 따옴표 안의 콤마는 챌린지 구분자로 보지 않는다 (WWW-Authenticate가 여러 scheme을
    // 콤마로 이어붙일 수 있다: `Bearer realm="...", Basic realm="..."`).
    private static final Pattern SPLIT_OUTSIDE_QUOTES = Pattern.compile(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");

    private final NcrProperties properties;
    private final RetryExecutor retryExecutor;
    private final RestClient restClient;

    public NcrRegistryClient(NcrProperties properties, RetryExecutor retryExecutor, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.retryExecutor = retryExecutor;
        this.restClient =
                restClientBuilder.baseUrl("https://" + properties.endpoint()).build();
    }

    // ponytail: skopeo에 --src-creds 인자로 그대로 넘기면 같은 호스트의 다른 프로세스가
    // ps/proc/<pid>/cmdline으로 볼 수 있다. Phase 4에서 실제로 skopeo를 호출할 때
    // REGISTRY_AUTH_FILE(0600) 방식으로 바꾸는 걸 검토할 것.
    /** skopeo {@code --src-creds}에 그대로 넣을 수 있는 {@code accessKey:secretKey} 문자열. */
    public String credentialsForCli() {
        return properties.accessKey() + ":" + properties.secretKey();
    }

    // ponytail: Bearer 토큰을 캐시하지 않아 매 호출마다 401→토큰발급→재요청 3 RTT다.
    // Phase 4가 이미지를 대량으로 순회하기 전에 GraphTokenService와 같은 만료 캐시를 추가할 것.
    /** Basic→Bearer 폴백을 거쳐 인증된 GET 응답 본문을 반환한다. 실패 시 재시도 정책을 적용한다. */
    public String get(String path) {
        return retryExecutor.execute("NCR GET " + path, () -> attemptGet(path));
    }

    /** {@code GET /v2/} 도달성만 확인한다. 200/401 모두 "도달 가능"으로 간주한다 (0.6·Phase 0 기준). */
    public boolean isReachable() {
        try {
            restClient.get().uri("/v2/").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException ex) {
            return ex.getStatusCode().value() == 401;
        } catch (ResourceAccessException ex) {
            log.warn("NCR({})에 연결할 수 없습니다: {}", properties.endpoint(), ex.getMessage());
            return false;
        }
    }

    public void healthCheck() {
        if (!isReachable()) {
            throw new ApiException(ErrorCode.REGISTRY_UNREACHABLE);
        }
    }

    private String attemptGet(String path) {
        RelativePathGuard.requireRelative(path);
        try {
            return doGet(path, basicAuthHeader());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                String wwwAuthenticate = ex.getResponseHeaders() == null
                        ? null
                        : ex.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
                if (wwwAuthenticate != null && wwwAuthenticate.regionMatches(true, 0, "Bearer", 0, 6)) {
                    String token = fetchBearerToken(wwwAuthenticate);
                    return doGetClassified(path, "Bearer " + token);
                }
            }
            throw classify(ex, path);
        } catch (ResourceAccessException ex) {
            throw timeoutRetryable(path, ex);
        }
    }

    // 401 이후 Bearer로 재요청하는 호출도 최초 호출과 동일하게 분류해야 한다. 예전 구현은
    // 이 두 번째 호출의 실패를 별도 catch로 처리하지 않아 ResourceAccessException이 분류
    // 없이 그대로 새어 나갔다(재시도 정책이 적용되지 않음) — 그 버그를 고친 자리다.
    private String doGetClassified(String path, String authorizationHeader) {
        try {
            return doGet(path, authorizationHeader);
        } catch (RestClientResponseException ex) {
            throw classify(ex, path);
        } catch (ResourceAccessException ex) {
            throw timeoutRetryable(path, ex);
        }
    }

    private String doGet(String path, String authorizationHeader) {
        return restClient
                .get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                .retrieve()
                .body(String.class);
    }

    private String basicAuthHeader() {
        String raw = properties.accessKey() + ":" + properties.secretKey();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String fetchBearerToken(String wwwAuthenticate) {
        Map<String, String> params = parseBearerChallengeParams(wwwAuthenticate);
        String realm = params.get("realm");
        if (realm == null) {
            log.warn("NCR Bearer 인증 realm을 찾을 수 없습니다.");
            throw new ApiException(ErrorCode.REGISTRY_UNAUTHORIZED);
        }

        URI realmUri;
        try {
            realmUri = URI.create(realm);
        } catch (IllegalArgumentException e) {
            log.warn("NCR Bearer realm URI가 올바르지 않습니다: {}", realm);
            throw new ApiException(ErrorCode.REGISTRY_UNAUTHORIZED);
        }
        // realm이 평문 HTTP면 Basic 자격 증명이 그대로 노출된다 — 절대 따라가지 않는다.
        // host는 고정하지 않는다: Docker Registry v2 스펙상 토큰 발급 서버가 레지스트리와
        // 다른 서브도메인이어도 정상이라, NCR의 실제 토폴로지를 모르는 채 좁히면 Phase 4
        // 실연동이 깨질 수 있다.
        if (!"https".equalsIgnoreCase(realmUri.getScheme())) {
            log.warn("NCR Bearer realm이 HTTPS가 아닙니다: {}", realm);
            throw new ApiException(ErrorCode.REGISTRY_UNAUTHORIZED);
        }

        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(realm);
        if (params.get("service") != null) {
            uri.queryParam("service", params.get("service"));
        }
        if (params.get("scope") != null) {
            uri.queryParam("scope", params.get("scope"));
        }

        try {
            TokenResponse response = restClient
                    .get()
                    .uri(uri.build().toUri())
                    .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                    .retrieve()
                    .body(TokenResponse.class);
            String token = response == null ? null : response.anyToken();
            if (token == null) {
                log.warn("NCR Bearer 토큰 응답이 비어 있습니다.");
                throw new ApiException(ErrorCode.REGISTRY_UNAUTHORIZED);
            }
            return token;
        } catch (RestClientResponseException ex) {
            log.warn("NCR Bearer 토큰 발급 실패: {}", ex.getStatusCode());
            throw new ApiException(ErrorCode.REGISTRY_UNAUTHORIZED);
        } catch (ResourceAccessException ex) {
            throw timeoutRetryable(realm, ex);
        }
    }

    private RuntimeException classify(RestClientResponseException ex, String path) {
        int status = ex.getStatusCode().value();
        log.warn("NCR 호출 실패({}): {}", status, path);
        if (status == 401 || status == 403) {
            return new ApiException(ErrorCode.REGISTRY_UNAUTHORIZED);
        }
        if (status >= 500 || status == 429) {
            return new RetryableCallException(
                    new ApiException(ErrorCode.REGISTRY_TIMEOUT), RetryAfterHeader.parseSeconds(ex.getResponseHeaders()));
        }
        // 404 등은 이 Phase의 책임 밖이다 (Phase 4가 FN-05/FN-06-1 오류 코드로 다시 분류한다).
        return ex;
    }

    private RetryableCallException timeoutRetryable(String path, ResourceAccessException ex) {
        log.warn("NCR 호출 시간 초과: {} ({})", path, ex.getMessage());
        return new RetryableCallException(new ApiException(ErrorCode.REGISTRY_TIMEOUT));
    }

    /**
     * {@code WWW-Authenticate} 헤더에서 "Bearer" challenge에 속한 파라미터만 추출한다.
     * 헤더 하나에 여러 scheme이 콤마로 이어질 수 있어({@code Bearer realm=...,
     * Basic realm=...}) 헤더 전체에 정규식을 돌리면 나중 scheme의 realm이 앞선 값을
     * 덮어써 엉뚱한 호스트로 자격 증명이 갈 수 있다 — Bearer 구간만 골라낸다.
     */
    private static Map<String, String> parseBearerChallengeParams(String header) {
        String[] segments = SPLIT_OUTSIDE_QUOTES.split(header);
        Map<String, String> params = new LinkedHashMap<>();
        int start = -1;
        for (int i = 0; i < segments.length; i++) {
            Matcher schemeMatcher = BEARER_SCHEME.matcher(segments[i].trim());
            if (schemeMatcher.matches()) {
                start = i;
                String remainder = schemeMatcher.group(1);
                if (!remainder.isBlank()) {
                    tryAddParam(params, remainder);
                }
                break;
            }
        }
        if (start == -1) {
            return Map.of();
        }
        for (int i = start + 1; i < segments.length; i++) {
            if (!tryAddParam(params, segments[i].trim())) {
                break; // 다음 scheme(challenge)의 시작 - 여기서 멈춘다.
            }
        }
        return params;
    }

    private static boolean tryAddParam(Map<String, String> params, String segment) {
        Matcher matcher = PARAM.matcher(segment);
        if (!matcher.matches()) {
            return false;
        }
        params.put(matcher.group(1), matcher.group(2));
        return true;
    }

    private record TokenResponse(String token, String access_token) {
        String anyToken() {
            return token != null ? token : access_token;
        }
    }
}
