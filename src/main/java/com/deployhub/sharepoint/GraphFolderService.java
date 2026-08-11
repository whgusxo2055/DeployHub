package com.deployhub.sharepoint;

import com.deployhub.common.ApiException;
import com.deployhub.job.service.PackageJobService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * 업로드 대상 폴더 확보. 폴더명은 항상 {@code versionName}이고 충돌해도 이름을 바꾸지 않는다 —
 * 경로로 먼저 조회해 있으면 재사용, 없으면 상위 경로 밑에 생성한다.
 * 공유 링크 type/scope는 운영 중 바뀌지 않는 값이라 환경변수로 노출하지 않고 상수로 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphFolderService {

    private static final Pattern FORBIDDEN_CHARS = Pattern.compile("[\"*:<>?/\\\\|]");
    private static final int MAX_NAME_LENGTH = 255;
    private static final String LINK_TYPE = "view";
    private static final String LINK_SCOPE = "organization";

    private final GraphApiClient graphApiClient;
    private final GraphProperties graphProperties;
    private final PackageJobService packageJobService;
    private final ObjectMapper objectMapper;

    /**
     * UPLOADING 단계 진입 시 1회 호출된다. {@code sp_folder_id}/{@code sp_folder_url}을 갱신하고
     * {@link GraphUploadService}가 바로 쓸 Drive Item ID를 반환한다.
     */
    public String ensureFolder(String versionName) {
        validateFolderName(versionName);
        String driveId = graphApiClient.resolveDriveId();

        FolderItem folder = graphApiClient
                .getOrNull(folderPath(driveId, versionName))
                .map(this::parseFolderItem)
                .orElseGet(() -> createFolder(driveId, versionName));

        clearExistingChildren(driveId, folder.id());
        String linkUrl = createShareLink(driveId, folder.id()).orElseGet(() -> {
            log.warn("E-1005: 조직 범위 공유 링크 발급이 차단되어 폴더 webUrl로 대체합니다: versionName={}", versionName);
            return folder.webUrl();
        });

        // 상태 변경은 서비스 계층의 @Transactional 메서드를 거친다 — 리포지토리를 직접 만지면
        // 비트랜잭션 조회~저장 사이에 다른 트랜잭션의 변경을 덮어쓴다.
        packageJobService.applyFolder(versionName, folder.id(), linkUrl);
        return folder.id();
    }

    private void validateFolderName(String name) {
        if (name.isBlank() || name.length() > MAX_NAME_LENGTH || !name.strip().equals(name)) {
            throw new IllegalStateException("E-1004: 폴더명 규칙을 위반했습니다: " + name);
        }
        // 전부 '.'인 이름(".", "..")은 SharePoint 경로 addressing에서 상위 경로를 가리킬 수 있다 —
        // 상위 계층 정규식이 이미 막지만 그 검증이 느슨해지는 미래를 대비한 방어층이다.
        if (name.chars().allMatch(c -> c == '.')) {
            throw new IllegalStateException("E-1004: 폴더명에 허용되지 않는 문자가 있습니다: " + name);
        }
        if (FORBIDDEN_CHARS.matcher(name).find()) {
            throw new IllegalStateException("E-1004: 폴더명에 허용되지 않는 문자가 있습니다: " + name);
        }
    }

    private FolderItem createFolder(String driveId, String versionName) {
        String parentItemId = resolveParentItemId(driveId);
        Map<String, Object> body = Map.of(
                "name", versionName,
                "folder", Map.of(),
                "@microsoft.graph.conflictBehavior", "fail");
        try {
            String response = graphApiClient.post("/drives/%s/items/%s/children".formatted(driveId, parentItemId), body);
            return parseFolderItem(response);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 409) {
                // 조회와 생성 사이에 누가 먼저 만든 것 — 실패가 아니라 재사용으로 본다.
                log.info("폴더 생성 경합(409) — 재조회하여 재사용합니다: versionName={}", versionName);
                return graphApiClient
                        .getOrNull(folderPath(driveId, versionName))
                        .map(this::parseFolderItem)
                        .orElseThrow(() ->
                                new IllegalStateException("E-1001: 폴더 생성 경합 후 재조회에 실패했습니다: " + versionName));
            }
            throw ex;
        }
    }

    private String resolveParentItemId(String driveId) {
        return graphApiClient
                .getOrNull("/drives/%s/root:%s".formatted(driveId, graphProperties.rootPath()))
                .map(this::parseFolderItem)
                .orElseThrow(() -> new IllegalStateException("E-1002: SharePoint 상위 경로가 없습니다: " + graphProperties.rootPath()))
                .id();
    }

    private String folderPath(String driveId, String versionName) {
        return "/drives/%s/root:%s/%s".formatted(driveId, graphProperties.rootPath(), versionName);
    }

    /** ponytail: nextLink를 따라가지 않는 대신 {@code $top=999}로 기본 페이지 크기보다 넉넉히 받는다. */
    private void clearExistingChildren(String driveId, String folderItemId) {
        String response = graphApiClient.get("/drives/%s/items/%s/children?$top=999".formatted(driveId, folderItemId));
        for (String childId : parseChildIds(response)) {
            graphApiClient.delete("/drives/%s/items/%s".formatted(driveId, childId));
        }
    }

    private List<String> parseChildIds(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<String> ids = new ArrayList<>();
            for (JsonNode child : root.path("value")) {
                ids.add(child.path("id").asText());
            }
            return ids;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Graph 폴더 목록 응답 파싱에 실패했습니다.", e);
        }
    }

    /**
     * 테넌트 정책으로 공유 링크가 막히면 예외 형태가 다양하다 —
     * 어느 쪽이든 Job을 막지 않고 폴더 webUrl로 대체한다.
     */
    private Optional<String> createShareLink(String driveId, String folderItemId) {
        Map<String, Object> body = Map.of("type", LINK_TYPE, "scope", LINK_SCOPE);
        try {
            String response = graphApiClient.post("/drives/%s/items/%s/createLink".formatted(driveId, folderItemId), body);
            return Optional.of(extractLinkWebUrl(response));
        } catch (RuntimeException ex) {
            log.warn("E-1005: 공유 링크 발급이 거부됐습니다: folderItemId={}, reason={}", folderItemId, describeBriefly(ex));
            return Optional.empty();
        }
    }

    /** 오류 응답 본문을 통째로 로그에 옮기지 않는다 — 상태 코드 정도만 남긴다. */
    private String describeBriefly(RuntimeException ex) {
        if (ex instanceof RestClientResponseException rex) {
            return "status=" + rex.getStatusCode().value();
        }
        if (ex instanceof ApiException apiEx) {
            return apiEx.getErrorCode().getCode();
        }
        return ex.getClass().getSimpleName();
    }

    private String extractLinkWebUrl(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String url = root.path("link").path("webUrl").asText(null);
            if (url == null || url.isBlank()) {
                throw new IllegalStateException("Graph 공유 링크 응답에 webUrl이 없습니다.");
            }
            return url;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Graph 공유 링크 응답 파싱에 실패했습니다.", e);
        }
    }

    private FolderItem parseFolderItem(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String id = root.path("id").asText(null);
            String webUrl = root.path("webUrl").asText(null);
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("Graph 폴더 응답에 id가 없습니다.");
            }
            return new FolderItem(id, webUrl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Graph 폴더 응답 파싱에 실패했습니다.", e);
        }
    }

    private record FolderItem(String id, String webUrl) {}
}
