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
        server.expect(requestTo("https://ncr.example.com/v2/repo/tags/list"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .header(
                                HttpHeaders.WWW_AUTHENTICATE,
                                "Bearer realm=\"https://auth.example.com/token\",service=\"ncr.example.com\",scope=\"pull\""));

        server.expect(requestTo("https://auth.example.com/token?service=ncr.example.com&scope=pull"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andRespond(withSuccess("{\"token\":\"tok-123\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://ncr.example.com/v2/repo/tags/list"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-123"))
                .andRespond(withSuccess("{\"tags\":[]}", MediaType.APPLICATION_JSON));

        String body = client.get("/v2/repo/tags/list");

        assertThat(body).contains("tags");
        server.verify();
    }

    @Test
    void 여러_scheme이_섞인_challenge에서_Bearer_구간만_읽는다() {
        // "Bearer realm=...", 뒤에 다른 scheme(Basic)이 콤마로 이어붙는 경우 —
        // 마지막 realm이 이기면 안 되고, Bearer 구간의 realm만 써야 한다.
        server.expect(requestTo("https://ncr.example.com/v2/repo/tags/list"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .header(
                                HttpHeaders.WWW_AUTHENTICATE,
                                "Bearer realm=\"https://auth.example.com/token\",service=\"ncr.example.com\", Basic realm=\"http://attacker.example.com/\""));

        server.expect(requestTo("https://auth.example.com/token?service=ncr.example.com"))
                .andRespond(withSuccess("{\"token\":\"tok-123\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://ncr.example.com/v2/repo/tags/list"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-123"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.get("/v2/repo/tags/list");

        server.verify(); // attacker.example.com으로는 아무 요청도 안 나감 (미등록 요청이면 실패함)
    }

    @Test
    void realm이_HTTPS가_아니면_자격증명을_보내지_않고_실패한다() {
        server.expect(requestTo("https://ncr.example.com/v2/repo/tags/list"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"http://auth.example.com/token\""));

        assertThatThrownBy(() -> client.get("/v2/repo/tags/list"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REGISTRY_UNAUTHORIZED);

        server.verify(); // realm으로는 아무 요청도 안 나감
    }

    @Test
    void 응답이_401이고_Bearer_challenge가_없으면_재시도_없이_즉시_실패한다() {
        server.expect(requestTo("https://ncr.example.com/v2/repo/tags/list"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.get("/v2/repo/tags/list"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.REGISTRY_UNAUTHORIZED);

        server.verify();
    }

    @Test
    void 응답이_5xx면_재시도_후에도_실패시_레지스트리_오류로_던진다() {
        server.expect(requestTo("https://ncr.example.com/v2/repo/tags/list"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("https://ncr.example.com/v2/repo/tags/list"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.get("/v2/repo/tags/list"))
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
                .andRespond(withSuccess("{\"layers\":[]}", MediaType.APPLICATION_JSON));

        client.getManifest("repo", "tag");

        server.verify();
    }

    @Test
    void 인덱스_응답이면_플랫폼_매니페스트를_따라가_레이어_크기를_합산한다() {
        String index =
                """
                {"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[
                  {"digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                   "platform":{"os":"unknown","architecture":"unknown"}},
                  {"digest":"sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                   "platform":{"os":"linux","architecture":"amd64"}}]}""";
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/tag"))
                .andRespond(withSuccess(index, MediaType.APPLICATION_JSON)
                        .header("Docker-Content-Digest", "sha256:index"));
        // 어테스테이션(unknown/unknown)이 아니라 linux/amd64 항목을 따라가야 한다.
        server.expect(requestTo(
                        "https://ncr.example.com/v2/repo/manifests/sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andExpect(header(HttpHeaders.ACCEPT, containsString("application/vnd.oci.image.index.v1+json")))
                .andRespond(withSuccess(
                        "{\"layers\":[{\"size\":10},{\"size\":32}]}", MediaType.APPLICATION_JSON));

        NcrRegistryClient.ManifestInfo info =
                client.getManifest("repo", "tag").orElseThrow();

        // digest는 태그가 가리키는 인덱스 digest 그대로여야 한다 (skopeo 비교 대상과 동일).
        assertThat(info.digest()).isEqualTo("sha256:index");
        assertThat(info.totalSize()).isEqualTo(42L);
        server.verify();
    }

    @Test
    void 인덱스는_있는데_자식_조회가_404여도_이미지_없음으로_보고하지_않는다() {
        // 자식 404가 그대로 올라가면 getManifest의 404 분기가 Optional.empty()로 삼켜,
        // 태그가 멀쩡히 존재하는데 E-0501("이미지가 존재하지 않습니다")로 오판한다.
        String index =
                """
                {"manifests":[{"digest":"sha256:0000000000000000000000000000000000000000000000000000000000000000",
                  "platform":{"os":"linux","architecture":"amd64"}}]}""";
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/tag"))
                .andRespond(withSuccess(index, MediaType.APPLICATION_JSON)
                        .header("Docker-Content-Digest", "sha256:index"));
        server.expect(requestTo(
                        "https://ncr.example.com/v2/repo/manifests/sha256:0000000000000000000000000000000000000000000000000000000000000000"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getManifest("repo", "tag")).isInstanceOf(ApiException.class);
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
                        .header("Docker-Content-Digest", "sha256:index"));

        // 0이 아니라 UNKNOWN_SIZE여야 한다 — 0으로 돌려주면 디스크 가드가 "필요 용량 0"으로
        // 읽어 무조건 통과한다.
        assertThat(client.getManifest("repo", "tag").orElseThrow().totalSize())
                .isEqualTo(NcrRegistryClient.ManifestInfo.UNKNOWN_SIZE);
        server.verify(); // 두 번째 요청이 나가면 미등록 요청으로 실패한다
    }

    @Test
    void 레이어_size가_음수나_거대값이어도_합계가_뒤집히지_않는다() {
        // 합계가 음수가 되면 FN-05 디스크 여유 판정이 무조건 통과해버린다.
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/tag"))
                .andRespond(withSuccess(
                        "{\"layers\":[{\"size\":-1},{\"size\":9223372036854775807},{\"size\":5}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.getManifest("repo", "tag").orElseThrow().totalSize())
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void 단일_매니페스트는_추가_조회_없이_그대로_합산한다() {
        server.expect(requestTo("https://ncr.example.com/v2/repo/manifests/tag"))
                .andRespond(withSuccess("{\"layers\":[{\"size\":7}]}", MediaType.APPLICATION_JSON)
                        .header("Docker-Content-Digest", "sha256:single"));

        NcrRegistryClient.ManifestInfo info =
                client.getManifest("repo", "tag").orElseThrow();

        assertThat(info.totalSize()).isEqualTo(7L);
        server.verify(); // 두 번째 요청이 나가면 미등록 요청으로 실패한다
    }

    @Test
    void Bearer_재요청_중_연결_실패는_재시도_대상으로_분류된다() {
        server.expect(requestTo("https://ncr.example.com/v2/repo/tags/list"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"https://auth.example.com/token\""));
        server.expect(requestTo("https://auth.example.com/token"))
                .andRespond(withSuccess("{\"token\":\"tok-123\"}", MediaType.APPLICATION_JSON));
        // Bearer로 재요청한 두 번째 호출이 연결 실패로 끊긴다 — 예전 구현은 이 예외가
        // 분류 없이 그대로 새어 나가 재시도가 걸리지 않는 버그가 있었다.
        server.expect(requestTo("https://ncr.example.com/v2/repo/tags/list"))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });
        server.expect(requestTo("https://ncr.example.com/v2/repo/tags/list"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, BASIC))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // maxRetries=1이므로 재시도는 처음부터(Basic) 다시 시작한다 — 재시도 대상으로
        // 분류되었다는 것 자체(= ApiException으로 안 죽고 재시도가 실제로 일어남)를 검증한다.
        String body = client.get("/v2/repo/tags/list");

        assertThat(body).isEqualTo("{}");
        server.verify();
    }
}
