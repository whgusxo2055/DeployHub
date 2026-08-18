package com.deployhub.sharepoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.deployhub.common.ItemErrorCode;
import com.deployhub.common.retry.RetryExecutor;
import com.deployhub.common.retry.RetryProperties;
import com.deployhub.job.entity.PackageItem;
import com.deployhub.job.entity.PackageItemStatus;
import com.deployhub.job.repository.PackageItemRepository;
import com.deployhub.registry.ImageReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** FN-09 (구현계획서 Phase 5 작업 항목 2) — 청크 분할·재시도를 MockRestServiceServer로 검증한다. */
class GraphUploadServiceTest {

    private static final GraphProperties PROPERTIES =
            new GraphProperties("tenant", "client", "secret", "site-1", "drive-1", "/Deploy/Packages");
    private static final String VERSION_NAME = "2026.09.01";
    private static final String IMAGE_TAG = "myrepo/foo:1.0.0";
    private static final String FOLDER_ITEM_ID = "folder-9";

    @TempDir
    Path workDir;

    private MockRestServiceServer server;
    private PackageItemRepository packageItemRepository;
    private String fileName;

    private GraphUploadService newService(long chunkSize) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        GraphTokenService tokenService = mock(GraphTokenService.class);
        when(tokenService.getAccessToken()).thenReturn("token");
        RetryExecutor retryExecutor =
                new RetryExecutor(new RetryProperties(2, List.of(Duration.ofMillis(1))), duration -> {});
        // 청크 전송용 클라이언트에도 같은 builder를 넘겨 MockRestServiceServer 하나가 둘 다 가로채게 한다.
        GraphApiClient graphApiClient =
                new GraphApiClient(PROPERTIES, tokenService, retryExecutor, new ObjectMapper(), builder, builder);
        return new GraphUploadService(
                packageItemRepository, graphApiClient, new RetryProperties(2, List.of(Duration.ofMillis(1))),
                new ObjectMapper(), workDir.toString(), chunkSize);
    }

    @BeforeEach
    void setUp() throws IOException {
        packageItemRepository = mock(PackageItemRepository.class);
        fileName = ImageReference.parse(IMAGE_TAG).tarFileName();
        Path imagesDir = workDir.resolve(VERSION_NAME).resolve("images");
        Files.createDirectories(imagesDir);
        Files.writeString(imagesDir.resolve(fileName), "0123456789012345678901234"); // 25 bytes
    }

    private PackageItem downloadedItem() {
        return PackageItem.builder()
                .versionName(VERSION_NAME)
                .imageTag(IMAGE_TAG)
                .status(PackageItemStatus.DOWNLOADED)
                .fileSize(25L)
                .build();
    }

    @Test
    void 파일을_청크_단위로_순서대로_업로드하고_UPLOADED로_전환한다() {
        PackageItem item = downloadedItem();
        when(packageItemRepository.findByVersionNameOrderByImageTagAsc(VERSION_NAME)).thenReturn(List.of(item));
        GraphUploadService service = newService(10); // 25바이트 → 10/10/5 청크 3개

        String sessionPath = "/drives/drive-1/items/%s:/%s:/createUploadSession".formatted(FOLDER_ITEM_ID, fileName);
        server.expect(requestTo("https://graph.microsoft.com/v1.0" + sessionPath))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"uploadUrl\":\"https://upload.example/session-1\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://upload.example/session-1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.CONTENT_RANGE, "bytes 0-9/25"))
                .andExpect(content().string("0123456789"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));
        server.expect(requestTo("https://upload.example/session-1"))
                .andExpect(header(HttpHeaders.CONTENT_RANGE, "bytes 10-19/25"))
                .andExpect(content().string("0123456789"))
                .andRespond(withStatus(HttpStatus.ACCEPTED));
        server.expect(requestTo("https://upload.example/session-1"))
                .andExpect(header(HttpHeaders.CONTENT_RANGE, "bytes 20-24/25"))
                .andExpect(content().string("01234"))
                .andRespond(withStatus(HttpStatus.CREATED).body("{\"webUrl\":\"https://sp/uploaded.tar\"}"));

        service.uploadAll(VERSION_NAME, FOLDER_ITEM_ID);

        assertThat(item.getStatus()).isEqualTo(PackageItemStatus.UPLOADED);
        assertThat(item.getFileUrl()).isEqualTo("https://sp/uploaded.tar");
        verify(packageItemRepository).save(item);
        server.verify();
    }

    @Test
    void 상태코드_429는_같은_청크를_재전송한다() {
        PackageItem item = downloadedItem();
        when(packageItemRepository.findByVersionNameOrderByImageTagAsc(VERSION_NAME)).thenReturn(List.of(item));
        GraphUploadService service = newService(100); // 25바이트 전체가 청크 1개

        server.expect(requestTo("https://graph.microsoft.com/v1.0/drives/drive-1/items/%s:/%s:/createUploadSession"
                        .formatted(FOLDER_ITEM_ID, fileName)))
                .andRespond(withSuccess("{\"uploadUrl\":\"https://upload.example/session-2\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://upload.example/session-2"))
                .andExpect(header(HttpHeaders.CONTENT_RANGE, "bytes 0-24/25"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo("https://upload.example/session-2"))
                .andExpect(header(HttpHeaders.CONTENT_RANGE, "bytes 0-24/25"))
                .andRespond(withStatus(HttpStatus.CREATED).body("{\"webUrl\":\"https://sp/uploaded2.tar\"}"));

        service.uploadAll(VERSION_NAME, FOLDER_ITEM_ID);

        assertThat(item.getStatus()).isEqualTo(PackageItemStatus.UPLOADED);
        server.verify();
    }

    @Test
    void 청크_전송이_타임아웃되면_세션을_새로_만들지_않고_같은_청크만_재전송한다() {
        // 타임아웃(ResourceAccessException)을 청크 루프 밖으로 올리면 바깥 재시도가 새 세션을 만들어
        // 파일을 처음부터 다시 올린다 — GB 단위 tar에서는 수렴하지 않는다. createUploadSession
        // expectation을 하나만 두어 세션 재생성이 없음을 고정한다.
        PackageItem item = downloadedItem();
        when(packageItemRepository.findByVersionNameOrderByImageTagAsc(VERSION_NAME)).thenReturn(List.of(item));
        GraphUploadService service = newService(100); // 25바이트 전체가 청크 1개

        server.expect(requestTo("https://graph.microsoft.com/v1.0/drives/drive-1/items/%s:/%s:/createUploadSession"
                        .formatted(FOLDER_ITEM_ID, fileName)))
                .andRespond(withSuccess("{\"uploadUrl\":\"https://upload.example/session-timeout\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://upload.example/session-timeout"))
                .andExpect(header(HttpHeaders.CONTENT_RANGE, "bytes 0-24/25"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));
        server.expect(requestTo("https://upload.example/session-timeout"))
                .andExpect(header(HttpHeaders.CONTENT_RANGE, "bytes 0-24/25"))
                .andRespond(withStatus(HttpStatus.CREATED).body("{\"webUrl\":\"https://sp/after-timeout.tar\"}"));

        service.uploadAll(VERSION_NAME, FOLDER_ITEM_ID);

        assertThat(item.getStatus()).isEqualTo(PackageItemStatus.UPLOADED);
        assertThat(item.getFileUrl()).isEqualTo("https://sp/after-timeout.tar");
        server.verify();
    }

    @Test
    void 이미_UPLOADED된_항목도_폴더가_비워진_뒤_다시_올린다() {
        // ensureFolder가 재사용 시 폴더를 통째로 비우므로(GraphFolderService.clearExistingChildren),
        // UPLOADED 항목을 건너뛰면 그 파일만 폴더에서 사라진 채로 남는다(코드리뷰로 발견된
        // 데이터 유실 버그의 회귀 방지) — uploadAll이 UPLOADED도 대상에 포함하는지 검증한다.
        PackageItem item = PackageItem.builder()
                .versionName(VERSION_NAME)
                .imageTag(IMAGE_TAG)
                .status(PackageItemStatus.UPLOADED)
                .fileSize(25L)
                .fileUrl("https://sp/old-upload.tar")
                .build();
        when(packageItemRepository.findByVersionNameOrderByImageTagAsc(VERSION_NAME)).thenReturn(List.of(item));
        GraphUploadService service = newService(100);

        server.expect(requestTo("https://graph.microsoft.com/v1.0/drives/drive-1/items/%s:/%s:/createUploadSession"
                        .formatted(FOLDER_ITEM_ID, fileName)))
                .andRespond(withSuccess("{\"uploadUrl\":\"https://upload.example/session-3\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://upload.example/session-3"))
                .andRespond(withStatus(HttpStatus.CREATED).body("{\"webUrl\":\"https://sp/re-uploaded.tar\"}"));

        service.uploadAll(VERSION_NAME, FOLDER_ITEM_ID);

        assertThat(item.getStatus()).isEqualTo(PackageItemStatus.UPLOADED);
        assertThat(item.getFileUrl()).isEqualTo("https://sp/re-uploaded.tar");
        server.verify();
    }

    @Test
    void 상태코드_416이면_세션_상태를_다시_조회해_그_오프셋부터_이어서_보낸다() {
        PackageItem item = downloadedItem();
        when(packageItemRepository.findByVersionNameOrderByImageTagAsc(VERSION_NAME)).thenReturn(List.of(item));
        GraphUploadService service = newService(10); // 25바이트 → 10/10/5 청크

        server.expect(requestTo("https://graph.microsoft.com/v1.0/drives/drive-1/items/%s:/%s:/createUploadSession"
                        .formatted(FOLDER_ITEM_ID, fileName)))
                .andRespond(withSuccess("{\"uploadUrl\":\"https://upload.example/session-4\"}", MediaType.APPLICATION_JSON));
        // 첫 청크(0-9)가 416 — 서버는 이미 0-19까지 받았다고 응답한다.
        server.expect(requestTo("https://upload.example/session-4"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.CONTENT_RANGE, "bytes 0-9/25"))
                .andRespond(withStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE));
        server.expect(requestTo("https://upload.example/session-4"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"nextExpectedRanges\":[\"20-24\"]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://upload.example/session-4"))
                .andExpect(header(HttpHeaders.CONTENT_RANGE, "bytes 20-24/25"))
                .andExpect(content().string("01234"))
                .andRespond(withStatus(HttpStatus.CREATED).body("{\"webUrl\":\"https://sp/resumed.tar\"}"));

        service.uploadAll(VERSION_NAME, FOLDER_ITEM_ID);

        assertThat(item.getStatus()).isEqualTo(PackageItemStatus.UPLOADED);
        assertThat(item.getFileUrl()).isEqualTo("https://sp/resumed.tar");
        server.verify();
    }

    @Test
    void createUploadSession이_403이면_재시도_없이_항목을_FAILED_처리한다() {
        // 권한 부족은 재시도해도 결과가 바뀌지 않는다 — 세션 생성 호출이 한 번만
        // 일어나는지(재시도로 예산을 태우지 않는지)를 MockRestServiceServer의 단일
        // expectation으로 검증한다(코드리뷰로 발견 — 예전엔 IllegalStateException만
        // 잡아서 이 ApiException이 항목 처리 없이 Job을 통째로 죽였다).
        PackageItem item = downloadedItem();
        when(packageItemRepository.findByVersionNameOrderByImageTagAsc(VERSION_NAME)).thenReturn(List.of(item));
        GraphUploadService service = newService(10);

        server.expect(requestTo("https://graph.microsoft.com/v1.0/drives/drive-1/items/%s:/%s:/createUploadSession"
                        .formatted(FOLDER_ITEM_ID, fileName)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> service.uploadAll(VERSION_NAME, FOLDER_ITEM_ID)).isInstanceOf(IllegalStateException.class);

        assertThat(item.getStatus()).isEqualTo(PackageItemStatus.FAILED);
        server.verify();
    }

    @Test
    void 세션_조회가_실패해도_Graph_응답_본문을_error_message에_남기지_않는다() {
        // error_message는 무인증 GET /api/package-jobs/{versionName} 응답에 그대로 실린다.
        // RestClientResponseException.getMessage()에는 업스트림 응답 본문이 통째로 들어 있어
        // 그대로 저장하면 Graph가 돌려준 내용이 외부로 새어 나간다.
        String upstreamBody = "{\"error\":\"tempauth=LEAKED_SESSION_TOKEN\"}";
        PackageItem item = downloadedItem();
        when(packageItemRepository.findByVersionNameOrderByImageTagAsc(VERSION_NAME)).thenReturn(List.of(item));
        GraphUploadService service = newService(10);

        // 416 → 세션 상태 재조회가 400 → 재시도 대상이라 maxRetries(2)만큼 더 돈다 = 총 3회
        for (int attempt = 0; attempt < 3; attempt++) {
            server.expect(requestTo("https://graph.microsoft.com/v1.0/drives/drive-1/items/%s:/%s:/createUploadSession"
                            .formatted(FOLDER_ITEM_ID, fileName)))
                    .andRespond(withSuccess(
                            "{\"uploadUrl\":\"https://upload.example/session-leak\"}", MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://upload.example/session-leak"))
                    .andExpect(method(HttpMethod.PUT))
                    .andRespond(withStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE));
            server.expect(requestTo("https://upload.example/session-leak"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST).body(upstreamBody));
        }

        assertThatThrownBy(() -> service.uploadAll(VERSION_NAME, FOLDER_ITEM_ID))
                .isInstanceOf(IllegalStateException.class);

        assertThat(item.getStatus()).isEqualTo(PackageItemStatus.FAILED);
        // 상태 코드는 이제 error_message가 아니라 로그(detail)로 간다 — DB 문구는 ItemErrorCode가 정한다.
        assertThat(item.getErrorMessage())
                .doesNotContain("LEAKED_SESSION_TOKEN")
                .isEqualTo(ItemErrorCode.UPLOAD_FAILED.toErrorMessage());
        server.verify();
    }

    @Test
    void 업로드_대상_파일이_없으면_HTTP_호출_없이_항목을_FAILED_처리한다() {
        PackageItem item = PackageItem.builder()
                .versionName(VERSION_NAME)
                .imageTag("missing/file:1.0.0")
                .status(PackageItemStatus.DOWNLOADED)
                .build();
        when(packageItemRepository.findByVersionNameOrderByImageTagAsc(VERSION_NAME)).thenReturn(List.of(item));
        GraphUploadService service = newService(10);

        assertThatThrownBy(() -> service.uploadAll(VERSION_NAME, FOLDER_ITEM_ID)).isInstanceOf(IllegalStateException.class);

        assertThat(item.getStatus()).isEqualTo(PackageItemStatus.FAILED);
        server.verify();
    }
}
