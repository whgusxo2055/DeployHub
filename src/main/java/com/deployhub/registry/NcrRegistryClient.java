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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * NCR 레지스트리 REST 클라이언트. Basic으로 시도하고 401+Bearer 챌린지를 받으면 realm에서 토큰을 받아 재요청한다.
 * {@link RestClient.Builder}를 주입받아야 {@code spring.http.client.*} 타임아웃이 붙는다(정적 create()는 무제한).
 */
@Slf4j
@Service
public class NcrRegistryClient {

    // OCI digest 문법(algorithm ":" encoded) — '/', '.', '?', '#', '{', '%'가 원천 배제된다.
    private static final Pattern DIGEST = Pattern.compile("[a-z0-9]+([.+_-][a-z0-9]+)*:[a-zA-Z0-9=_-]{32,}");
    private static final Pattern PARAM = Pattern.compile("^(\\w+)\\s*=\\s*\"([^\"]*)\"$");
    private static final Pattern BEARER_SCHEME = Pattern.compile("(?i)^bearer\\b\\s*(.*)$");
    // 따옴표 안의 콤마는 챌린지 구분자로 보지 않는다 (WWW-Authenticate가 여러 scheme을
    // 콤마로 이어붙일 수 있다: `Bearer realm="...", Basic realm="..."`).
    private static final Pattern SPLIT_OUTSIDE_QUOTES = Pattern.compile(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");

    // Boot 빈이 아니라 기본 설정 매퍼를 쓴다 — 미지의 필드에 실패하는 엄격 모드가 의도다(TokenResponse 참고).
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DIGEST_HEADER = "Docker-Content-Digest";
    // 단일 매니페스트 2종 + 인덱스 2종을 모두 보낼 것. 하나라도 빠지면 레지스트리가 있는 이미지를
    // 404(MANIFEST_UNKNOWN)로 돌려줘 "이미지 없음"으로 오판한다.
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

    // 통합 테스트가 평문 HTTP(registry:2)를 가리켜야 해서 스킴을 허용한다. 스킴 없는 운영 엔드포인트는 https가 붙는다.
    private static String resolveBaseUrl(String endpoint) {
        return endpoint.contains("://") ? endpoint : "https://" + endpoint;
    }

    // ponytail: Bearer 토큰을 캐시하지 않아 매 호출이 401→토큰발급→재요청 3 RTT다.
    // 대량 순회가 병목이 되면 GraphTokenService 같은 만료 캐시를 붙일 것.
    /** 인증된 GET 응답 본문. 실패 시 재시도 정책을 적용한다. */
    /**
     * 매니페스트의 digest와 레이어 크기 합계(무결성 대조·디스크 판정 기준값).
     * 404만 {@link Optional#empty()}로 돌려 호출자가 배치를 계속하게 하고, 나머지는 예외로 던진다.
     */
    public Optional<ManifestInfo> getManifest(ImageReference ref) {
        // 파싱된 참조만 받는다 — 검증되지 않은 문자열 2개를 받으면 "호출 전에 parse할 것"이라는
        // 규약이 주석에만 남는다. RelativePathGuard는 ".."나 "{"를 걸러주지 않는다.
        String path = "/v2/%s/manifests/%s".formatted(ref.repository(), ref.tag());
        try {
            return Optional.of(retryExecutor.execute(
                    "NCR manifest " + path, () -> authenticated(path, auth -> doGetManifest(path, auth))));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    /**
     * Basic으로 먼저 호출하고, {@code 401 + WWW-Authenticate: Bearer}면 그 realm에서 토큰을
     * 받아 한 번 재호출한다(Docker Registry v2 토큰 인증). 두 시도 모두 같은 규칙으로 분류한다.
     */
    private <T> T authenticated(String path, Function<String, T> call) {
        RelativePathGuard.requireRelative(path);
        String bearerChallenge;
        try {
            return call.apply(basicAuthHeader());
        } catch (RestClientResponseException ex) {
            bearerChallenge = bearerChallengeOf(ex);
            if (bearerChallenge == null) {
                throw classify(ex, path);
            }
        } catch (ResourceAccessException ex) {
            throw timeoutRetryable(path, ex);
        }
        String token = fetchBearerToken(bearerChallenge);
        try {
            return call.apply("Bearer " + token);
        } catch (RestClientResponseException ex) {
            throw classify(ex, path);
        } catch (ResourceAccessException ex) {
            throw timeoutRetryable(path, ex);
        }
    }

    /** 401 + Bearer 챌린지면 그 헤더를, 아니면 null(= Bearer 폴백 대상이 아님). */
    private static String bearerChallengeOf(RestClientResponseException ex) {
        if (ex.getStatusCode().value() != 401 || ex.getResponseHeaders() == null) {
            return null;
        }
        // 헤더가 여러 줄로 오거나 "Basic ..., Bearer ..." 순서일 수 있다 — 파서는 그걸 처리하도록
        // 만들어져 있는데 진입 조건만 "Bearer로 시작"이면 그 대응이 사장된다.
        List<String> headers = ex.getResponseHeaders().get(HttpHeaders.WWW_AUTHENTICATE);
        if (headers == null) {
            return null;
        }
        // getValuesAsList는 따옴표를 무시하고 콤마로 쪼개 realm="...",service="..."를 망가뜨린다.
        for (String header : headers) {
            if (header != null && header.toLowerCase(Locale.ROOT).contains("bearer")) {
                return header;
            }
        }
        return null;
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
        // 헤더가 없거나 형식이 다르면 여기서 끊는다 — null을 통과시키면 검증은 성공하고,
        // 다운로드 직후 digest 대조에서 전 항목이 E-0603("재푸시 의심")으로 오진된다.
        // 실제 레지스트리는 항상 이 헤더를 보내므로 정상 경로에는 영향이 없다.
        if (digest == null || !DIGEST.matcher(digest).matches()) {
            log.warn("NCR 응답에 {} 헤더가 없거나 형식이 올바르지 않습니다: {}", DIGEST_HEADER, path);
            throw new ApiException(ErrorCode.REGISTRY_UNREACHABLE);
        }
        return new ManifestInfo(digest, totalSize(response.getBody(), path, authorizationHeader));
    }

    /**
     * 레이어 크기 합계. 인덱스에는 {@code layers}가 없어 자식 매니페스트를 각각 조회해 더한다 —
     * skopeo를 {@code --multi-arch all}로 돌려 플랫폼을 고르지 않고 전 항목을 아카이브에 담으므로,
     * buildx 어테스테이션(platform이 unknown/unknown)도 빼지 않고 합산해야 실제 크기와 맞는다.
     * 못 구하면 0이 아니라 {@link ManifestInfo#UNKNOWN_SIZE}다 — 0이면 디스크 가드가 fail-open 된다.
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
        long total = 0L;
        for (JsonNode entry : manifests) {
            String childDigest = entry.path("digest").asText(null);
            // digest는 응답 본문 값이라 경로에 붙기 전 반드시 문법 검사 — 경로 탈출·쿼리 인젝션·
            // URI 템플릿 변수가 여기서 걸러진다.
            if (childDigest == null || !DIGEST.matcher(childDigest).matches()) {
                log.warn("NCR 인덱스 항목의 digest 형식이 올바르지 않아 크기를 미상으로 처리합니다: {}", path);
                return ManifestInfo.UNKNOWN_SIZE;
            }
            JsonNode child = fetchChildManifest(path, childDigest, authorizationHeader);
            if (child == null) {
                return ManifestInfo.UNKNOWN_SIZE;
            }
            // 자식에 layers가 없으면(중첩 인덱스 등) 0이 아니라 미상이다 — 아래 sumLayerSizes 참고.
            if (!child.path("layers").isArray()) {
                log.warn("NCR 인덱스 자식에 layers가 없어 크기를 미상으로 처리합니다: {}", path);
                return ManifestInfo.UNKNOWN_SIZE;
            }
            long childTotal = sumLayerSizes(child);
            total = total + childTotal < total ? Long.MAX_VALUE : total + childTotal;
        }
        return total;
    }

    /**
     * ponytail: 인덱스 → 매니페스트 1단계만 따라간다. 중첩 인덱스는 실물이 드물어 미지원.
     *
     * <p>자식 조회 실패는 {@code null}로 돌려 <b>크기만 미상</b>으로 만든다 — 예외로 올리면 인덱스
     * 이미지 하나가 배치 전체(형제 태그 11개)의 검증을 중단시키고, 바깥 재시도가 부모부터 다시 받아
     * 요청이 (1+자식수)×시도 수로 증폭된다. 401/403만은 자격증명 문제라 그대로 올린다.
     */
    private JsonNode fetchChildManifest(String path, String childDigest, String authorizationHeader) {
        String childPath = path.substring(0, path.lastIndexOf('/') + 1) + childDigest;
        RelativePathGuard.requireRelative(childPath); // 방어층 — 형식 검증은 호출부가 이미 했다
        try {
            return readTree(restClient
                    .get()
                    .uri(childPath)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .header(HttpHeaders.ACCEPT, MANIFEST_ACCEPT)
                    .retrieve()
                    .body(String.class));
        } catch (RestClientResponseException ex) {
            RuntimeException classified = classify(ex, childPath);
            if (classified instanceof ApiException apiException
                    && apiException.getErrorCode() == ErrorCode.REGISTRY_UNAUTHORIZED) {
                throw apiException;
            }
            log.warn("NCR 인덱스 자식 매니페스트 조회 실패({}) — 크기를 미상으로 처리합니다: {}",
                    ex.getStatusCode().value(), childPath);
            return null;
        } catch (ResourceAccessException ex) {
            log.warn("NCR 인덱스 자식 매니페스트 조회 시간 초과 — 크기를 미상으로 처리합니다: {}", childPath);
            return null;
        }
    }

    /**
     * size는 응답 본문 값이라 음수·거대값이 올 수 있다 — 합계가 뒤집히면 디스크 판정이 무조건
     * 통과하므로 음수는 버리고 포화 덧셈으로 누적한다.
     *
     * <p>{@code layers}가 아예 없으면 0이 아니라 {@link ManifestInfo#UNKNOWN_SIZE}다.
     * {@code MissingNode}는 빈 순회라 0을 돌려주는데, 0은 "필요 용량 0"으로 읽혀
     * <b>디스크 가드가 항상 통과</b>한다(fail-open). 형태가 기대와 다르면 미상이 안전하다.
     */
    private static long sumLayerSizes(JsonNode manifest) {
        JsonNode layers = manifest.path("layers");
        if (!layers.isArray()) {
            log.warn("NCR 매니페스트에 layers 배열이 없어 크기를 미상으로 처리합니다.");
            return ManifestInfo.UNKNOWN_SIZE;
        }
        long total = 0L;
        for (JsonNode layer : layers) {
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
            log.warn("NCR 매니페스트 응답을 파싱할 수 없어 크기를 미상으로 처리합니다.", e);
            return null;
        }
    }

    /** {@code GET /v2/} 도달성만 확인한다. 200/401 모두 "도달 가능"이다. */
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
        // realm으로 자격 증명이 Basic 헤더에 담겨 나간다 — 401 헤더를 통제하는 쪽이 임의 호스트를
        // 지정하면 그대로 받아간다. realm 원문은 서버가 통제하는 문자열이라 로그에 넣지 않는다.
        String registryHost = URI.create(resolveBaseUrl(properties.endpoint())).getHost();
        if (registryHost == null || !registryHost.equalsIgnoreCase(realmUri.getHost())) {
            log.warn("NCR Bearer realm 호스트가 레지스트리와 다릅니다.");
            throw new ApiException(ErrorCode.REGISTRY_UNAUTHORIZED);
        }

        try {
            // service/scope는 서버가 준 값이라 toUri()가 던질 수 있다 — try 안에 둬서 분류되지 않은
            // 예외가 배치를 중단시키지 않게 한다. 검사를 마친 realmUri를 그대로 쓴다(fromUriString은 더 느슨하다).
            // 값은 업스트림 문자열이라 인코딩해서 붙인다 — 안 하면 값 안의 '&'가 파라미터를 가른다.
            UriComponentsBuilder uri = UriComponentsBuilder.fromUri(realmUri).encode();
            if (params.get("service") != null) {
                uri.queryParam("service", params.get("service"));
            }
            if (params.get("scope") != null) {
                uri.queryParam("scope", params.get("scope"));
            }

            // NCR 토큰 엔드포인트는 JSON을 담고도 Content-Type을 text/plain으로 준다 —
            // .body(TokenResponse.class)로 바인딩하면 Jackson 컨버터가 안 붙어 죽는다. String으로 받아 직접 파싱한다.
            String body = restClient
                    .get()
                    .uri(uri.build().toUri())
                    .header(HttpHeaders.AUTHORIZATION, basicAuthHeader())
                    .retrieve()
                    .body(String.class);
            // 본문이 JSON 리터럴 null이면 readValue가 null을 돌려준다 — 이어 붙이면 NPE가 나고
            // 그 NPE는 아래 catch 어디에도 안 걸려 배치를 통째로 중단시킨다.
            TokenResponse parsed = body == null ? null : OBJECT_MAPPER.readValue(body, TokenResponse.class);
            String token = parsed == null ? null : parsed.anyToken();
            if (token == null) {
                log.warn("NCR Bearer 토큰 응답이 비어 있습니다.");
                throw new ApiException(ErrorCode.REGISTRY_UNAUTHORIZED);
            }
            return token;
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            // 본문이 JSON이 아닌 건 인증 실패가 아니라 도달성 문제다(차단 장비가 평문 응답을 끼워 넣는 경로).
            // 본문은 로그에 남기지 않는다.
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
        return ex; // 404 등은 호출자가 자기 맥락으로 다시 분류한다.
    }

    private RetryableCallException timeoutRetryable(String path, ResourceAccessException ex) {
        log.warn("NCR 호출 시간 초과: {} ({})", path, ex.getMessage());
        return new RetryableCallException(new ApiException(ErrorCode.REGISTRY_TIMEOUT));
    }

    /**
     * Bearer challenge에 속한 파라미터만 추출한다. 헤더 하나에 여러 scheme이 이어질 수 있어
     * 전체에 정규식을 돌리면 뒤 scheme의 realm이 앞을 덮어써 엉뚱한 호스트로 자격 증명이 간다.
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

    // NCR이 expires_in/issued_at도 함께 주는데 OBJECT_MAPPER가 엄격 모드라 이게 없으면 파싱이 깨진다.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(String token, String access_token) {
        String anyToken() {
            return token != null ? token : access_token;
        }
    }

    /** 확정 시점 스냅샷 — {@code digest}는 무결성 대조, {@code totalSize}는 디스크 판정 기준값이다. */
    public record ManifestInfo(String digest, long totalSize) {

        /** 크기 미상. 0("크기 0")과 반드시 구분해야 디스크 가드가 fail-open 되지 않는다. */
        public static final long UNKNOWN_SIZE = -1L;

        public boolean hasUnknownSize() {
            return totalSize < 0;
        }
    }
}
