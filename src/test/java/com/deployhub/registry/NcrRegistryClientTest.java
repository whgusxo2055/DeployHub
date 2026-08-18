package com.deployhub.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.common.retry.RetryExecutor;
import com.deployhub.common.retry.RetryProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Docker Registry v2 토큰 인증 폴백(Basic → 401 → Bearer)과 오류 분류를 검증한다. */
class NcrRegistryClientTest {

    private static final NcrProperties PROPERTIES =
            new NcrProperties("ncr.example.com", "AK", "SK", "/usr/bin/skopeo");
    private static final String BASIC = "Basic " + Base64.getEncoder().encodeToString("AK:SK".getBytes());
    private static final String DIGEST_HEADER = "Docker-Content-Digest";
    // 실제 레지스트리가 주는 형식이어야 한다 — 짧은 더미는 문법 검증에서 걸린다.
    private static final String SINGLE_DIGEST = "sha256:1111111111111111111111111111111111111111111111111111111111111111";
    private static final String INDEX_DIGEST = "sha256:2222222222222222222222222222222222222222222222222222222222222222";

    private MockRestServiceServer server;
    private NcrRegistryClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RetryExecutor retryExecutor =
                new RetryExecutor(new RetryProperties(1, List.of(Duration.ofMillis(1))), duration -> {});
        client = new NcrRegistryClient(PROPERTIES, retryExecutor, builder);
    }

    @Test
    void v2_경로가_200이면_도달_가능으로_판단한다() {
        server.expect(requestTo("https://ncr.example.com/v2/")).andRespond(withSuccess());

        assertThat(client.isReachable()).isTrue();
    }

    @Test
    void v2_경로가_401이어도_도달_가능으로_판단한다() {
        server.expect(requestTo("https://ncr.example.com/v2/")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(client.isReachable()).isTrue();
    }

    @Test
    void Basic_인증이_401이면_realm에서_토큰을_받아_Bearer로_재요청한다() {
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/v1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .header(
                                HttpHeaders.WWW_AUTHENTICATE,
                                "Bearer realm=\"https://ncr.example.com/auth/token\",service=\"ncr.example.com\",scope=\"pull\""));

        server.expect(requestTo("https://ncr.example.com/auth/token?service=ncr.example.com&scope=pull"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andRespond(withSuccess("{\"token\":\"tok-123\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/v1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-123"))
                .andRespond(manifestResponse());

        assertThat(client.getManifest(new ImageReference("repo", "v1")).orElseThrow().digest()).isEqualTo(SINGLE_DIGEST);
        server.verify();
    }

    @Test
    void 토큰_응답이_text_plain이고_미지의_필드가_있어도_파싱한다() {
        // dev-ncr-sb 실측: NCR은 JSON을 담고도 Content-Type을 text/plain으로 주고,
        // 본문에 expires_in/issued_at을 함께 넣는다. 이 테스트가 없던 동안 나머지
        // 목킹이 전부 APPLICATION_JSON이라 실연동에서만 UnknownContentTypeException이 났다.
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/v1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .header(
                                HttpHeaders.WWW_AUTHENTICATE,
                                "Bearer realm=\"https://ncr.example.com/auth/token\",service=\"ncr\""));

        server.expect(requestTo("https://ncr.example.com/auth/token?service=ncr"))
                .andRespond(withSuccess(
                        "{\"token\":\"tok-123\",\"expires_in\":3600,\"issued_at\":\"2026-08-07T03:49:44Z\"}",
                        MediaType.TEXT_PLAIN));

        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/v1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-123"))
                .andRespond(manifestResponse());

        assertThat(client.getManifest(new ImageReference("repo", "v1"))).isPresent();
        server.verify();
    }

    @Test
    void 토큰_응답이_JSON이_아니면_도달성_오류로_분류된다() {
        // 사내망 차단 장비가 평문/HTML을 끼워 넣는 경우 — 자격 증명 문제가 아니므로
        // E-0401(인증 실패)이 아니라 E-0404(도달 불가)로 떨어져야 오진을 안 만든다.
        expectChallenge();
        server.expect(requestTo("https://ncr.example.com/auth/token?service=ncr"))
                .andRespond(withSuccess("<html>blocked</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.getManifest(new ImageReference("repo", "v1")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REGISTRY_UNREACHABLE);
    }

    @Test
    void 토큰_응답이_JSON_리터럴_null이면_NPE가_아니라_분류된_예외가_난다() {
        // readValue("null", ...)은 예외가 아니라 Java null을 돌려준다 — 이 경로에서
        // NPE가 새면 RetryExecutor를 그대로 통과해 검증 배치가 통째로 중단된다.
        expectChallenge();
        server.expect(requestTo("https://ncr.example.com/auth/token?service=ncr"))
                .andRespond(withSuccess("null", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.getManifest(new ImageReference("repo", "v1"))).isInstanceOf(ApiException.class);
    }

    /** 레지스트리는 매니페스트 응답에 항상 digest 헤더를 준다 — 성공 응답의 기본형. */
    private static org.springframework.test.web.client.response.DefaultResponseCreator manifestResponse() {
        return withSuccess("{\"layers\":[{\"size\":7}]}", MediaType.APPLICATION_JSON)
                .header(DIGEST_HEADER, SINGLE_DIGEST);
    }

    private void expectChallenge() {
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/v1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .header(
                                HttpHeaders.WWW_AUTHENTICATE,
                                "Bearer realm=\"https://ncr.example.com/auth/token\",service=\"ncr\""));
    }

    @Test
    void realm이_다른_호스트면_자격증명을_보내지_않고_실패한다() {
        // 이 realm으로 accessKey/secretKey가 Basic 헤더에 담겨 나간다 — 401 헤더를 통제할
        // 수 있는 쪽이 임의 호스트를 지정하면 자격 증명을 그대로 받아간다.
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/v1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .header(
                                HttpHeaders.WWW_AUTHENTICATE,
                                "Bearer realm=\"https://attacker.example.com/token\",service=\"ncr\""));

        assertThatThrownBy(() -> client.getManifest(new ImageReference("repo", "v1"))).isInstanceOf(ApiException.class);
        // attacker.example.com으로는 요청이 안 나간다 — 미등록 요청이면 여기서 실패한다.
        server.verify();
    }

    @Test
    void 여러_scheme이_섞인_challenge에서_Bearer_구간만_읽는다() {
        // "Bearer realm=...", 뒤에 다른 scheme(Basic)이 콤마로 이어붙는 경우 —
        // 마지막 realm이 이기면 안 되고, Bearer 구간의 realm만 써야 한다.
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/v1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .header(
                                HttpHeaders.WWW_AUTHENTICATE,
                                "Bearer realm=\"https://ncr.example.com/auth/token\",service=\"ncr.example.com\", Basic realm=\"http://attacker.example.com/\""));

        server.expect(requestTo("https://ncr.example.com/auth/token?service=ncr.example.com"))
                .andRespond(withSuccess("{\"token\":\"tok-123\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/v1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-123"))
                .andRespond(manifestResponse());

        client.getManifest(new ImageReference("repo", "v1"));

        server.verify(); // attacker.example.com으로는 아무 요청도 안 나감 (미등록 요청이면 실패함)
    }

    @Test
    void realm이_HTTPS가_아니면_자격증명을_보내지_않고_실패한다() {
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/v1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"http://auth.example.com/token\""));

        assertThatThrownBy(() -> client.getManifest(new ImageReference("repo", "v1")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REGISTRY_UNAUTHORIZED);

        server.verify(); // realm으로는 아무 요청도 안 나감
    }

    @Test
    void 응답이_401이고_Bearer_challenge가_없으면_재시도_없이_즉시_실패한다() {
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/v1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.getManifest(new ImageReference("repo", "v1")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REGISTRY_UNAUTHORIZED);

        server.verify();
    }

    @Test
    void 응답이_5xx면_재시도_후에도_실패시_레지스트리_오류로_던진다() {
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/v1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/v1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.getManifest(new ImageReference("repo", "v1")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REGISTRY_TIMEOUT);

        server.verify(); // maxRetries=1 → 최초 1회 + 재시도 1회 = 총 2회 호출
    }

    @Test
    void 매니페스트_Accept에_인덱스_미디어타입도_포함한다() {
        // 인덱스 타입이 빠지면 레지스트리가 404(MANIFEST_UNKNOWN)를 돌려주고, getManifest는
        // 404를 Optional.empty()로 처리하므로 실제 있는 이미지가 "없음"으로 오판된다.
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/tag"))
                .andExpect(header(HttpHeaders.ACCEPT, containsString("application/vnd.oci.image.index.v1+json")))
                .andExpect(header(
                        HttpHeaders.ACCEPT,
                        containsString("application/vnd.docker.distribution.manifest.list.v2+json")))
                .andRespond(withSuccess("{\"layers\":[]}", MediaType.APPLICATION_JSON)
                        .header(DIGEST_HEADER, SINGLE_DIGEST));

        client.getManifest(new ImageReference("repo", "tag"));

        server.verify();
    }

    @Test
    void 인덱스_응답이면_어테스테이션까지_포함해_전_항목의_레이어_크기를_합산한다() {
        String index =
                """
                {"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[
                  {"digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                   "platform":{"os":"unknown","architecture":"unknown"}},
                  {"digest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                   "platform":{"os":"linux","architecture":"amd64"}}]}""";
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/tag"))
                .andRespond(withSuccess(index, MediaType.APPLICATION_JSON)
                        .header("Docker-Content-Digest", INDEX_DIGEST));
        // skopeo를 --multi-arch all로 돌려 인덱스를 통째로 담으므로, 어테스테이션(unknown/unknown)도
        // 아카이브에 들어간다 — 빼고 더하면 디스크 가드가 실제보다 작게 잡는다.
        server.expect(requestTo(
                        "https://ncr.example.com/v2/repo/manifests/sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andRespond(withSuccess("{\"layers\":[{\"size\":8}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "https://ncr.example.com/v2/repo/manifests/sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andExpect(header(HttpHeaders.ACCEPT, containsString("application/vnd.oci.image.index.v1+json")))
                .andRespond(withSuccess(
                        "{\"layers\":[{\"size\":10},{\"size\":32}]}", MediaType.APPLICATION_JSON));

        NcrRegistryClient.ManifestInfo info =
                client.getManifest(new ImageReference("repo", "tag")).orElseThrow();

        // digest는 태그가 가리키는 인덱스 digest 그대로여야 한다 (skopeo 비교 대상과 동일).
        assertThat(info.digest()).isEqualTo(INDEX_DIGEST);
        assertThat(info.totalSize()).isEqualTo(50L);
        server.verify();
    }

    @Test
    void 인덱스_자식_조회가_404여도_이미지는_존재로_보고하고_크기만_미상으로_둔다() {
        // 자식 404가 그대로 올라가면 (a) getManifest의 404 분기가 Optional.empty()로 삼켜
        // 태그가 멀쩡히 존재하는데 E-0501로 오판하거나, (b) 예외가 되어 인덱스 이미지 하나가
        // 형제 태그 전체의 검증까지 중단시킨다. 둘 다 아니고, 크기만 미상이어야 한다.
        String index =
                """
                {"manifests":[{"digest":"sha256:0000000000000000000000000000000000000000000000000000000000000000",
                  "platform":{"os":"linux","architecture":"amd64"}}]}""";
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/tag"))
                .andRespond(withSuccess(index, MediaType.APPLICATION_JSON)
                        .header("Docker-Content-Digest", INDEX_DIGEST));
        server.expect(requestTo(
                        "https://ncr.example.com/v2/repo/manifests/sha256:0000000000000000000000000000000000000000000000000000000000000000"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getManifest(new ImageReference("repo", "tag")).orElseThrow().totalSize())
                .isEqualTo(NcrRegistryClient.ManifestInfo.UNKNOWN_SIZE);
        server.verify();
    }

    @Test
    void 인덱스의_digest가_digest_문법이_아니면_추가_조회하지_않는다() {
        // digest는 응답 본문 값이라 push 권한자가 임의 문자열을 넣을 수 있다. 그대로 경로에
        // 붙이면 같은 호스트 내 경로 탈출이 되고, "{"가 섞이면 Spring이 URI 템플릿 변수로
        // 해석해 ApiException이 아닌 예외로 검증 배치 전체가 죽는다.
        String index =
                """
                {"manifests":[
                  {"digest":"../../../v2/other/manifests/latest","platform":{"os":"linux","architecture":"amd64"}},
                  {"digest":"sha256:abc{evil}","platform":{"os":"linux","architecture":"amd64"}}]}""";
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/tag"))
                .andRespond(withSuccess(index, MediaType.APPLICATION_JSON)
                        .header("Docker-Content-Digest", INDEX_DIGEST));

        // 0이 아니라 UNKNOWN_SIZE여야 한다 — 0으로 돌려주면 디스크 가드가 "필요 용량 0"으로
        // 읽어 무조건 통과한다.
        assertThat(client.getManifest(new ImageReference("repo", "tag")).orElseThrow().totalSize())
                .isEqualTo(NcrRegistryClient.ManifestInfo.UNKNOWN_SIZE);
        server.verify(); // 두 번째 요청이 나가면 미등록 요청으로 실패한다
    }

    @Test
    void 레이어_size가_음수나_거대값이어도_합계가_뒤집히지_않는다() {
        // 합계가 음수가 되면 FN-05 디스크 여유 판정이 무조건 통과해버린다.
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/tag"))
                .andRespond(withSuccess(
                        "{\"layers\":[{\"size\":-1},{\"size\":9223372036854775807},{\"size\":5}]}",
                        MediaType.APPLICATION_JSON)
                        .header(DIGEST_HEADER, SINGLE_DIGEST));

        assertThat(client.getManifest(new ImageReference("repo", "tag")).orElseThrow().totalSize())
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void 단일_매니페스트는_추가_조회_없이_그대로_합산한다() {
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/tag"))
                .andRespond(withSuccess("{\"layers\":[{\"size\":7}]}", MediaType.APPLICATION_JSON)
                        .header("Docker-Content-Digest", SINGLE_DIGEST));

        NcrRegistryClient.ManifestInfo info =
                client.getManifest(new ImageReference("repo", "tag")).orElseThrow();

        assertThat(info.totalSize()).isEqualTo(7L);
        server.verify(); // 두 번째 요청이 나가면 미등록 요청으로 실패한다
    }

    @Test
    void layers가_아예_없는_매니페스트는_크기를_0이_아니라_미상으로_돌려준다() {
        // 0을 돌려주면 "필요 용량 0"으로 읽혀 디스크 가드가 항상 통과한다(fail-open).
        // config만 있고 layers 키가 없는 형태 — 형식이 기대와 다르면 미상이 안전하다.
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/tag"))
                .andRespond(withSuccess("{\"config\":{\"size\":3}}", MediaType.APPLICATION_JSON)
                        .header(DIGEST_HEADER, SINGLE_DIGEST));

        assertThat(client.getManifest(new ImageReference("repo", "tag")).orElseThrow().totalSize())
                .isEqualTo(NcrRegistryClient.ManifestInfo.UNKNOWN_SIZE);
    }

    @Test
    void digest_헤더가_없으면_도달성_오류로_끊는다() {
        // null digest를 통과시키면 검증은 성공하고, 다운로드 직후 대조에서 전 항목이
        // E-0603("재푸시 의심")으로 오진된다 — 원인과 표시가 완전히 어긋난다.
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/tag"))
                .andRespond(withSuccess("{\"layers\":[{\"size\":7}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getManifest(new ImageReference("repo", "tag")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REGISTRY_UNREACHABLE);
    }

    @Test
    void 없는_태그는_404를_Optional_empty로_돌려준다() {
        // E-0501("이미지 없음") 판정의 근거. 예외로 새면 배치 전체가 중단된다.
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/tag"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getManifest(new ImageReference("repo", "tag"))).isEmpty();
    }

    @Test
    void Bearer로_재요청한_결과가_404여도_Optional_empty다() {
        // 실 레지스트리 경로는 항상 401 → 토큰 → 재요청이다. 이 조합에서 404 분기가
        // 살아 있는지는 Basic만 쓰는 테스트로는 검증되지 않는다.
        expectChallenge();
        server.expect(requestTo("https://ncr.example.com/auth/token?service=ncr"))
                .andRespond(withSuccess("{\"token\":\"tok-123\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/v1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-123"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getManifest(new ImageReference("repo", "v1"))).isEmpty();
        server.verify();
    }

    @Test
    void Bearer_재요청_중_연결_실패는_재시도_대상으로_분류된다() {
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/v1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"https://ncr.example.com/auth/token\""));
        server.expect(requestTo("https://ncr.example.com/auth/token"))
                .andRespond(withSuccess("{\"token\":\"tok-123\"}", MediaType.APPLICATION_JSON));
        // Bearer로 재요청한 두 번째 호출이 연결 실패로 끊긴다 — 예전 구현은 이 예외가
        // 분류 없이 그대로 새어 나가 재시도가 걸리지 않는 버그가 있었다.
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/v1"))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/v1"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andRespond(manifestResponse());

        // maxRetries=1이므로 재시도는 처음부터(Basic) 다시 시작한다 — 재시도 대상으로
        // 분류되었다는 것 자체(= ApiException으로 안 죽고 재시도가 실제로 일어남)를 검증한다.
        assertThat(client.getManifest(new ImageReference("repo", "v1"))).isPresent();
        server.verify();
    }
}
