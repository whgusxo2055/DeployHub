package com.deployhub.registry;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.common.RelativePathGuard;
import com.deployhub.common.retry.RetryAfterHeader;
import com.deployhub.common.retry.RetryExecutor;
import com.deployhub.common.retry.RetryableCallException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
 * 인증 흐름). CLI 경로(skopeo, Phase 4)는 {@code PackageDownloadService}가
 * {@code NcrProperties} 자격 증명으로 REGISTRY_AUTH_FILE(0600 임시 파일)을 직접 만들어
 * 쓴다 — {@code --src-creds} 인자로 넘기면 같은 호스트의 다른 프로세스가
 * {@code ps}/{@code /proc/<pid>/cmdline}으로 자격 증명을 볼 수 있어서다.
 *
 * <p>생성자로 {@link RestClient.Builder}를 주입받는다 — Spring Boot가 자동 구성하는 빈을
 * 쓰면 {@code spring.http.client.*} 타임아웃이 그대로 적용된다(정적 {@code RestClient.create()}를
 * 쓰면 타임아웃이 무제한이 된다).
 */
@Slf4j
@Component
public class NcrRegistryClient {

    // OCI digest 문법(algorithm ":" encoded) — '/', '.', '?', '#', '{', '%'가 원천 배제된다.
    private static final Pattern DIGEST = Pattern.compile("[a-z0-9]+([.+_-][a-z0-9]+)*:[a-zA-Z0-9=_-]{32,}");
    private static final Pattern PARAM = Pattern.compile("^(\\w+)\\s*=\\s*\"([^\"]*)\"$");
    private static final Pattern BEARER_SCHEME = Pattern.compile("(?i)^bearer\\b\\s*(.*)$");
    // 따옴표 안의 콤마는 챌린지 구분자로 보지 않는다 (WWW-Authenticate가 여러 scheme을
    // 콤마로 이어붙일 수 있다: `Bearer realm="...", Basic realm="..."`).
    private static final Pattern SPLIT_OUTSIDE_QUOTES = Pattern.compile(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DIGEST_HEADER = "Docker-Content-Digest";
    // 구현계획서 459행은 Docker v2 schema2만 예시로 들지만, 실측해보니 최신 skopeo/buildx가
    // 기본으로 push하는 이미지는 OCI 포맷이다 — schema2 Accept만 보내면 레지스트리가
    // "존재하지만 변환 못 함"을 404(MANIFEST_UNKNOWN)로 돌려줘 실제 있는 이미지를 없는
    // 것으로 오판한다(E-0501 오탐). OCI manifest도 layers[].size 구조가 동일해 파싱 로직은
    // 그대로 재사용된다.
    //
    // 인덱스(멀티아치) 타입도 반드시 함께 보낼 것 — dev-ncr-sb 실측에서 저장소 12개 중 3개가
    // OCI index로 저장돼 있었고, 인덱스 타입이 빠지면 레지스트리가 그 사실을 명시한 404를
    // 돌려준다("OCI index found, but accept header does not support OCI indexes").
    private static final String MANIFEST_ACCEPT = String.join(
            ",",
            "application/vnd.docker.distribution.manifest.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.index.v1+json");

    private final NcrProperties properties;
    private final RetryExecutor retryExecutor;
    private final RestClient restClient;

    public NcrRegistryClient(NcrProperties properties, RetryExecutor retryExecutor, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.retryExecutor = retryExecutor;
        this.restClient = restClientBuilder.baseUrl(resolveBaseUrl(properties.endpoint())).build();
    }

    // Phase 4 로컬 레지스트리 통합 테스트가 평문 HTTP(registry:2)를 가리켜야 해서 스킴을
    // 받아들인다. 운영 NCR_ENDPOINT(0.6절 예시 "{registry}.kr.ncr.ntruss.com")는 스킴이
    // 없으므로 기존처럼 https://가 붙어 동작이 그대로다.
    private static String resolveBaseUrl(String endpoint) {
        return endpoint.contains("://") ? endpoint : "https://" + endpoint;
    }

    // ponytail: Bearer 토큰을 캐시하지 않아 매 호출마다 401→토큰발급→재요청 3 RTT다.
    // Phase 4가 이미지를 대량으로 순회하기 전에 GraphTokenService와 같은 만료 캐시를 추가할 것.
    /** Basic→Bearer 폴백을 거쳐 인증된 GET 응답 본문을 반환한다. 실패 시 재시도 정책을 적용한다. */
    public String get(String path) {
        return retryExecutor.execute("NCR GET " + path, () -> attemptGet(path));
    }

    /**
     * FN-05 매니페스트 존재 확인 (구현계획서 456-465행). {@code Docker-Content-Digest}
     * 헤더와 {@code layers[].size} 합계를 반환한다 — Phase 4-2의 무결성 대조·디스크 여유
     * 공간 판정 기준값이다. 404는 예외가 아니라 {@link Optional#empty()}로 돌려준다 —
     * "이 항목만 표시, 검증은 계속"(E-0501)이라 호출자가 배치 전체를 중단하면 안 된다.
     * 401/403/타임아웃은 {@link #get(String)}과 동일하게 {@link ApiException}으로 던진다
     * — 호출자가 그 자체로 검증 중단(E-0502)·누락 간주(E-0503)를 구분해 처리한다.
     */
    public Optional<ManifestInfo> getManifest(String repository, String tag) {
        String path = "/v2/%s/manifests/%s".formatted(repository, tag);
        try {
            return Optional.of(retryExecutor.execute("NCR manifest " + path, () -> attemptGetManifest(path)));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    private ManifestInfo attemptGetManifest(String path) {
        RelativePathGuard.requireRelative(path);
        try {
            return doGetManifest(path, basicAuthHeader());
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 401) {
                String wwwAuthenticate = ex.getResponseHeaders() == null
                        ? null
                        : ex.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
                if (wwwAuthenticate != null && wwwAuthenticate.regionMatches(true, 0, "Bearer", 0, 6)) {
                    String token = fetchBearerToken(wwwAuthenticate);
                    return doGetManifestClassified(path, "Bearer " + token);
                }
            }
            throw classify(ex, path);
        } catch (ResourceAccessException ex) {
            throw timeoutRetryable(path, ex);
        }
    }

    private ManifestInfo doGetManifestClassified(String path, String authorizationHeader) {
        try {
            return doGetManifest(path, authorizationHeader);
        } catch (RestClientResponseException ex) {
            throw classify(ex, path);
        } catch (ResourceAccessException ex) {
            throw timeoutRetryable(path, ex);
        }
    }

    private ManifestInfo doGetManifest(String path, String authorizationHeader) {
        ResponseEntity<String> response = restClient
                .get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                .header(HttpHeaders.ACCEPT, MANIFEST_ACCEPT)
                .retrieve()
                .toEntity(String.class);
        String digest = response.getHeaders().getFirst(DIGEST_HEADER);
        return new ManifestInfo(digest, totalSize(response.getBody(), path, authorizationHeader));
    }

    /**
     * 레이어 크기 합계. 인덱스(멀티아치)면 플랫폼 매니페스트를 한 번 더 조회해 합산한다 —
     * 인덱스 자체에는 {@code layers}가 없어 그대로 더하면 0이 된다. 반환하는 {@code digest}는
     * 태그가 가리키는 인덱스 digest 그대로 둔다 — 레지스트리·skopeo가 비교하는 값과 같아야
     * 하기 때문이다.
     *
     * <p>크기를 못 구한 경우 0이 아니라 {@link ManifestInfo#UNKNOWN_SIZE}를 반환한다. 0으로
     * 돌려주면 호출자의 디스크 여유 판정이 "필요 용량 0"으로 읽어 무조건 통과시킨다 —
     * 가드가 조용히 fail-open 된다.
     */
    private long totalSize(String manifestJson, String path, String authorizationHeader) {
        JsonNode root = readTree(manifestJson);
        if (root == null) {
            return ManifestInfo.UNKNOWN_SIZE;
        }
        JsonNode manifests = root.path("manifests");
        if (!manifests.isArray() || manifests.isEmpty()) {
            return sumLayerSizes(root);
        }
        String childDigest = selectPlatformDigest(manifests);
        if (childDigest == null) {
            log.warn("NCR 인덱스에서 쓸 플랫폼 매니페스트를 찾지 못해 크기를 미상으로 처리합니다: {}", path);
            return ManifestInfo.UNKNOWN_SIZE;
        }
        // ponytail: 인덱스 → 매니페스트 1단계만 따라간다. 중첩 인덱스는 실물이 드물어 미지원.
        String childPath = path.substring(0, path.lastIndexOf('/') + 1) + childDigest;
        RelativePathGuard.requireRelative(childPath); // 방어층 — 형식 검증은 selectPlatformDigest가 이미 했다
        JsonNode child;
        try {
            child = readTree(restClient
                    .get()
                    .uri(childPath)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .header(HttpHeaders.ACCEPT, MANIFEST_ACCEPT)
                    .retrieve()
                    .body(String.class));
        } catch (RestClientResponseException ex) {
            // 인덱스는 200인데 자식만 실패한 상황이다. RestClientResponseException을 그대로
            // 올리면 getManifest의 404 분기가 삼켜 "이미지 없음"(E-0501)으로 오판한다 —
            // 이 수정이 없애려던 실패 모드가 그대로 되살아난다. 반드시 다른 타입으로 바꿔 던진다.
            RuntimeException classified = classify(ex, childPath);
            throw classified instanceof RestClientResponseException
                    ? new ApiException(ErrorCode.REGISTRY_UNREACHABLE)
                    : classified;
        } catch (ResourceAccessException ex) {
            throw timeoutRetryable(childPath, ex);
        }
        return child == null ? ManifestInfo.UNKNOWN_SIZE : sumLayerSizes(child);
    }

    /**
     * linux/amd64를 우선 고르고 없으면 첫 실제 플랫폼 항목을 쓴다. buildx가 이미지와 함께
     * push하는 어테스테이션 항목은 {@code platform}이 unknown/unknown이라 반드시 걸러야 한다 —
     * 그걸 고르면 이미지가 아니라 서명 blob 크기를 더하게 된다.
     *
     * <p>{@code digest}는 응답 본문에서 온 값이라 레지스트리에 push 권한이 있으면 임의 문자열을
     * 넣을 수 있다. 이 값이 그대로 경로에 붙으므로 반드시 digest 문법을 검사할 것 —
     * {@code ../}(같은 호스트 내 경로 탈출), {@code ?}·{@code #}(쿼리·프래그먼트 인젝션),
     * {@code {}}(Spring URI 템플릿 변수로 해석돼 {@code IllegalArgumentException}이 나고,
     * 그 예외가 {@code ApiException}이 아니라서 검증 배치 전체를 중단시킨다)가 모두 걸러진다.
     */
    private static String selectPlatformDigest(JsonNode manifests) {
        String fallback = null;
        for (JsonNode entry : manifests) {
            JsonNode platform = entry.path("platform");
            String os = platform.path("os").asText("");
            String arch = platform.path("architecture").asText("");
            String digest = entry.path("digest").asText(null);
            if (digest == null || !DIGEST.matcher(digest).matches() || "unknown".equals(os) || "unknown".equals(arch)) {
                continue;
            }
            if ("linux".equals(os) && "amd64".equals(arch)) {
                return digest;
            }
            if (fallback == null) {
                fallback = digest;
            }
        }
        return fallback;
    }

    // size도 응답 본문 값이라 음수·거대값이 올 수 있다. 그대로 더하면 합계가 음수로 뒤집혀
    // FN-05 디스크 여유 판정이 무조건 통과한다 — 음수는 버리고 포화 덧셈으로 누적한다.
    private static long sumLayerSizes(JsonNode manifest) {
        long total = 0L;
        for (JsonNode layer : manifest.path("layers")) {
            long size = Math.max(0L, layer.path("size").asLong(0L));
            total = total + size < total ? Long.MAX_VALUE : total + size;
        }
        return total;
    }

    private static JsonNode readTree(String manifestJson) {
        if (manifestJson == null || manifestJson.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(manifestJson);
        } catch (Exception e) {
            log.warn("NCR 매니페스트 응답을 파싱할 수 없어 크기를 0으로 처리합니다.", e);
            return null;
        }
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
            log.warn("NCR Bearer realm URI가 올바르지 않습니다.");
            throw new ApiException(ErrorCode.REGISTRY_UNAUTHORIZED);
        }
        // realm이 평문 HTTP면 Basic 자격 증명이 그대로 노출된다 — 절대 따라가지 않는다.
        if (!"https".equalsIgnoreCase(realmUri.getScheme())) {
            log.warn("NCR Bearer realm이 HTTPS가 아닙니다.");
            throw new ApiException(ErrorCode.REGISTRY_UNAUTHORIZED);
        }
        // 이 realm으로 accessKey/secretKey가 Basic 헤더에 담겨 나간다 — 401 응답 헤더를
        // 통제할 수 있는 쪽이 임의 호스트를 지정하면 자격 증명을 그대로 받아간다.
        // dev-ncr-sb 실측상 NCR realm은 https://<registry-host>/auth/token으로 레지스트리와
        // 동일 호스트라(CLAUDE.md), "다른 서브도메인일 수 있다"던 기존 우려는 부정됐다.
        // realm 원문은 서버가 통제하는 문자열이라 로그에 넣지 않는다(로그 인젝션).
        String registryHost = URI.create(resolveBaseUrl(properties.endpoint())).getHost();
        if (registryHost == null || !registryHost.equalsIgnoreCase(realmUri.getHost())) {
            log.warn("NCR Bearer realm 호스트가 레지스트리와 다릅니다.");
            throw new ApiException(ErrorCode.REGISTRY_UNAUTHORIZED);
        }

        try {
            // challenge에서 온 service/scope는 서버가 준 값이다 — 공백·제어문자가 섞이면
            // toUri()가 IllegalArgumentException을 던진다. try 안에 둬서 분류되지 않은
            // 예외가 검증 배치를 통째로 중단시키지 않게 한다. 이미 스킴까지 검사한
            // realmUri를 그대로 쓴다(fromUriString은 파서가 더 느슨해 검사와 어긋날 수 있다).
            UriComponentsBuilder uri = UriComponentsBuilder.fromUri(realmUri);
            if (params.get("service") != null) {
                uri.queryParam("service", params.get("service"));
            }
            if (params.get("scope") != null) {
                uri.queryParam("scope", params.get("scope"));
            }

            // 본문을 String으로 받아 직접 파싱한다 — NCR의 토큰 엔드포인트는 JSON을
            // 담고도 Content-Type을 text/plain으로 준다(dev-ncr-sb 실측). 타입 바인딩
            // (.body(TokenResponse.class))을 쓰면 Jackson 컨버터가 붙지 않아
            // UnknownContentTypeException으로 죽는다.
            String body = restClient
                    .get()
                    .uri(uri.build().toUri())
                    .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                    .retrieve()
                    .body(String.class);
            // 본문이 JSON 리터럴 null이면 readValue는 예외가 아니라 null을 돌려준다 —
            // 한 줄로 이어 붙이면 anyToken()에서 NPE가 나고, NPE는 아래 catch 어디에도
            // 안 걸려 RetryExecutor를 그대로 통과해 검증 배치를 통째로 중단시킨다.
            TokenResponse parsed = body == null ? null : OBJECT_MAPPER.readValue(body, TokenResponse.class);
            String token = parsed == null ? null : parsed.anyToken();
            if (token == null) {
                log.warn("NCR Bearer 토큰 응답이 비어 있습니다.");
                throw new ApiException(ErrorCode.REGISTRY_UNAUTHORIZED);
            }
            return token;
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            // 본문이 JSON이 아닌 건 인증 실패가 아니다 — 자격 증명을 의심하게 만드는
            // E-0401 대신 도달성 문제로 분류한다. 사내망 차단 장비가 평문 응답을 끼워
            // 넣는 경우가 정확히 이 경로다(CLAUDE.md). 본문은 로그에 남기지 않는다.
            log.warn("NCR Bearer 토큰 응답을 해석할 수 없습니다: {}", ex.getClass().getSimpleName());
            throw new ApiException(ErrorCode.REGISTRY_UNREACHABLE);
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

    // NCR은 expires_in/issued_at도 함께 준다 — OBJECT_MAPPER는 기본 설정(미지의 필드에 실패)이라
    // 이 애너테이션이 없으면 토큰 파싱이 통째로 깨진다.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(String token, String access_token) {
        String anyToken() {
            return token != null ? token : access_token;
        }
    }

    /** FN-05 확정 시점 스냅샷. {@code digest}는 Phase 4-2 무결성 대조, {@code totalSize}는 디스크 판정 기준값이다. */
    public record ManifestInfo(String digest, long totalSize) {

        /** 크기를 못 구했다는 표시. 0("크기 0")과 반드시 구분해야 디스크 가드가 fail-open 되지 않는다. */
        public static final long UNKNOWN_SIZE = -1L;

        public boolean hasUnknownSize() {
            return totalSize < 0;
        }
    }
}
