package com.deployhub.sharepoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.deployhub.common.retry.RetryExecutor;
import com.deployhub.common.retry.RetryProperties;
import com.deployhub.job.service.PackageJobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** FN-08 (구현계획서 Phase 5 작업 항목 1) — GraphApiClientTest와 동일하게 MockRestServiceServer로 검증한다. */
class GraphFolderServiceTest {

    private static final GraphProperties PROPERTIES =
            new GraphProperties("tenant", "client", "secret", "site-1", "drive-1", "/Deploy/Packages");
    private static final String BASE = "https://graph.microsoft.com/v1.0";

    private MockRestServiceServer server;
    private PackageJobService packageJobService;
    private GraphFolderService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        GraphTokenService tokenService = mock(GraphTokenService.class);
        when(tokenService.getAccessToken()).thenReturn("token");
        RetryExecutor retryExecutor =
                new RetryExecutor(new RetryProperties(1, List.of(Duration.ofMillis(1))), duration -> {});
        GraphApiClient graphApiClient = new GraphApiClient(PROPERTIES, tokenService, retryExecutor, new ObjectMapper(), builder, builder);
        packageJobService = mock(PackageJobService.class);
        service = new GraphFolderService(graphApiClient, PROPERTIES, packageJobService, new ObjectMapper());
    }

    @Test
    void 폴더가_있으면_재사용하고_생성_요청을_보내지_않는다() {
        String versionName = "2026.08.05";

        server.expect(requestTo(BASE + "/drives/drive-1/root:/Deploy/Packages/" + versionName))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"folder-1\",\"webUrl\":\"https://sp/folder-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/drives/drive-1/items/folder-1/children?$top=999"))
                .andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/drives/drive-1/items/folder-1/createLink"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"link\":{\"webUrl\":\"https://sp/link-1\"}}", MediaType.APPLICATION_JSON));

        String folderItemId = service.ensureFolder(versionName);

        assertThat(folderItemId).isEqualTo("folder-1");
        verify(packageJobService).applyFolder(versionName, "folder-1", "https://sp/link-1");
        server.verify();
    }

    @Test
    void 폴더가_없으면_상위_경로_밑에_생성한다() {
        String versionName = "2026.08.06";

        server.expect(requestTo(BASE + "/drives/drive-1/root:/Deploy/Packages/" + versionName))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE + "/drives/drive-1/root:/Deploy/Packages"))
                .andRespond(withSuccess("{\"id\":\"parent-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/drives/drive-1/items/parent-1/children"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"folder-2\",\"webUrl\":\"https://sp/folder-2\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/drives/drive-1/items/folder-2/children?$top=999"))
                .andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/drives/drive-1/items/folder-2/createLink"))
                .andRespond(withSuccess("{\"link\":{\"webUrl\":\"https://sp/link-2\"}}", MediaType.APPLICATION_JSON));

        String folderItemId = service.ensureFolder(versionName);

        assertThat(folderItemId).isEqualTo("folder-2");
        server.verify();
    }

    @Test
    void 생성_중_409_충돌이면_재조회해서_재사용한다() {
        String versionName = "2026.08.07";

        server.expect(requestTo(BASE + "/drives/drive-1/root:/Deploy/Packages/" + versionName))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE + "/drives/drive-1/root:/Deploy/Packages"))
                .andRespond(withSuccess("{\"id\":\"parent-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/drives/drive-1/items/parent-1/children"))
                .andRespond(withStatus(HttpStatus.CONFLICT));
        server.expect(requestTo(BASE + "/drives/drive-1/root:/Deploy/Packages/" + versionName))
                .andRespond(withSuccess("{\"id\":\"folder-3\",\"webUrl\":\"https://sp/folder-3\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/drives/drive-1/items/folder-3/children?$top=999"))
                .andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/drives/drive-1/items/folder-3/createLink"))
                .andRespond(withSuccess("{\"link\":{\"webUrl\":\"https://sp/link-3\"}}", MediaType.APPLICATION_JSON));

        String folderItemId = service.ensureFolder(versionName);

        assertThat(folderItemId).isEqualTo("folder-3");
        server.verify();
    }

    @Test
    void 공유_링크_발급이_거부되면_폴더_webUrl로_대체한다() {
        String versionName = "2026.08.08";

        server.expect(requestTo(BASE + "/drives/drive-1/root:/Deploy/Packages/" + versionName))
                .andRespond(withSuccess("{\"id\":\"folder-4\",\"webUrl\":\"https://sp/folder-4\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/drives/drive-1/items/folder-4/children?$top=999"))
                .andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/drives/drive-1/items/folder-4/createLink"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        service.ensureFolder(versionName);

        verify(packageJobService).applyFolder(versionName, "folder-4", "https://sp/folder-4");
    }

    @Test
    void 재사용_시_기존_파일을_모두_지운다() {
        String versionName = "2026.08.09";

        server.expect(requestTo(BASE + "/drives/drive-1/root:/Deploy/Packages/" + versionName))
                .andRespond(withSuccess("{\"id\":\"folder-5\",\"webUrl\":\"https://sp/folder-5\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/drives/drive-1/items/folder-5/children?$top=999"))
                .andRespond(withSuccess(
                        "{\"value\":[{\"id\":\"file-a\"},{\"id\":\"file-b\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/drives/drive-1/items/file-a"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(BASE + "/drives/drive-1/items/file-b"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(BASE + "/drives/drive-1/items/folder-5/createLink"))
                .andRespond(withSuccess("{\"link\":{\"webUrl\":\"https://sp/link-5\"}}", MediaType.APPLICATION_JSON));

        service.ensureFolder(versionName);

        server.verify();
    }

    @Test
    void 폴더명에_금지된_문자가_있으면_HTTP_호출_없이_예외를_던진다() {
        assertThatThrownBy(() -> service.ensureFolder("2026/08/10"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("E-1004");
        verify(packageJobService, never()).applyFolder(any(), any(), any());
    }

    @Test
    void 폴더명이_전부_점이면_HTTP_호출_없이_예외를_던진다() {
        assertThatThrownBy(() -> service.ensureFolder(".."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("E-1004");
        verify(packageJobService, never()).applyFolder(any(), any(), any());
    }
}
