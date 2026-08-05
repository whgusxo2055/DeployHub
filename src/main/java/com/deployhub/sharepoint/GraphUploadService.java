package com.deployhub.sharepoint;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.common.retry.RetryProperties;
import com.deployhub.job.entity.PackageItem;
import com.deployhub.job.entity.PackageItemStatus;
import com.deployhub.job.repository.PackageItemRepository;
import com.deployhub.registry.ImageReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * FN-09 파일 업로드 (구현계획서 Phase 5 작업 항목 2, 541-561행). 파일별로 순차 업로드하고,
 * 이미지 {@code .tar}는 통상 GB 단위라 업로드 세션 경로만 쓴다(250MB 이하 단순 PUT은
 * 다루지 않는다 — P1 범위 산출물이 전량 Docker Image라 파일이 그보다 작을 일이 없다).
 *
 * <p>세션이 소멸하면(E-1102) 이어받기가 불가능하다 — 바깥쪽 재시도 루프가 매번
 * {@link #uploadFile}을 처음부터 다시 호출해 새 세션을 만든다(구현계획서 "세션 재생성
 * 후 해당 파일 처음부터"). 중단 후 재개(416, E-1103)만 세션을 유지한 채 이어간다.
 */
@Slf4j
@Service
public class GraphUploadService {

    // E-1103(416) 무한루프 방지 — 실 테넌트 없이는 이 경로의 실제 동작을 검증할 수 없어
    // 보수적으로 작게 잡는다. 구현계획서 완료 기준(573행, 업로드 중단 후 재개)은 Graph
    // 앱 연동 준비 후 재검증이 필요하다. 청크 하나가 성공할 때마다 0으로 되돌린다 —
    // 아니면 500청크짜리 파일에서 서로 다른 지점의 일시적 416이 누적돼 파일 전체가
    // 실패한다(코드리뷰로 발견).
    private static final int MAX_RANGE_MISMATCH_RETRIES = 3;

    private final PackageItemRepository packageItemRepository;
    private final GraphApiClient graphApiClient;
    private final RetryProperties retryProperties;
    private final ObjectMapper objectMapper;
    private final String workDir;
    private final long chunkSize;

    public GraphUploadService(
            PackageItemRepository packageItemRepository,
            GraphApiClient graphApiClient,
            RetryProperties retryProperties,
            ObjectMapper objectMapper,
            @Value("${deployhub.work-dir}") String workDir,
            @Value("${deployhub.upload.chunk-size:10485760}") long chunkSize) {
        this.packageItemRepository = packageItemRepository;
        this.graphApiClient = graphApiClient;
        this.retryProperties = retryProperties;
        this.objectMapper = objectMapper;
        this.workDir = workDir;
        this.chunkSize = chunkSize;
    }

    /**
     * {@code folderItemId}는 {@link GraphFolderService#ensureFolder}가 확보한 폴더의 Drive
     * Item ID다. {@code ensureFolder}는 재사용 시 폴더를 비우므로(533행), 이미 {@code UPLOADED}인
     * 항목도 다시 올려야 폴더 내용이 {@code package_item} 목록과 일치한다(코드리뷰로 발견된
     * 데이터 유실 버그 — 그전엔 UPLOADED 항목을 건너뛰어 폴더에서 사라진 채로 남았다). 로컬
     * {@code .tar}는 Job이 DONE에 도달하지 않는 한 정리 배치(Phase 6)가 지우지 않으므로 항상 남아 있다.
     */
    public void uploadAll(String versionName, String folderItemId) {
        String driveId = graphApiClient.resolveDriveId();
        List<PackageItem> targets = packageItemRepository.findByVersionNameOrderByImageTagAsc(versionName).stream()
                .filter(item -> item.getStatus() == PackageItemStatus.DOWNLOADED
                        || item.getStatus() == PackageItemStatus.UPLOADED)
                .toList();

        boolean allSucceeded = true;
        for (PackageItem item : targets) {
            if (!uploadItemWithRetry(item, driveId, folderItemId)) {
                allSucceeded = false;
            }
        }
        if (!allSucceeded) {
            throw new IllegalStateException("일부 항목 업로드에 실패했습니다.");
        }
    }

    private boolean uploadItemWithRetry(PackageItem item, String driveId, String folderItemId) {
        ImageReference ref;
        try {
            ref = ImageReference.parse(item.getImageTag());
        } catch (IllegalArgumentException e) {
            return failItem(item, "E-0501: image_tag 형식이 올바르지 않습니다.", e.getMessage());
        }
        String fileName = ref.tarFileName();
        Path tarPath = Path.of(workDir, item.getVersionName(), "images", fileName);
        long fileSize;
        try {
            fileSize = Files.size(tarPath);
        } catch (IOException e) {
            return failItem(item, "E-0604: 업로드할 파일을 찾을 수 없습니다.", e.getMessage());
        }

        int attempt = 0;
        while (true) {
            try {
                String fileUrl = uploadFile(driveId, folderItemId, fileName, tarPath, fileSize);
                item.markUploaded(fileUrl);
                packageItemRepository.save(item);
                return true;
            } catch (RuntimeException e) {
                boolean retryable = isRetryable(e);
                attempt++;
                if (!retryable || attempt > retryProperties.maxRetries()) {
                    return failItem(item, describeFailure(e), null);
                }
                item.incrementRetryCount();
                packageItemRepository.save(item);
                sleep(retryProperties.backoffFor(attempt));
            }
        }
    }

    /**
     * 토큰 발급 실패·권한 부족은 재시도해도 결과가 바뀌지 않는다 — 재시도 예산을 태우지
     * 않고 바로 항목을 실패시킨다. {@code PackageDownloadService.isRetryable}(stderr 정규식
     * 기반)과 판정 대상은 다르지만 의도는 같다: "재시도해도 결과가 안 바뀌는 오류"만 걸러낸다.
     */
    private boolean isRetryable(RuntimeException e) {
        if (e instanceof ApiException apiEx) {
            return apiEx.getErrorCode() != ErrorCode.GRAPH_TOKEN_ISSUE_FAILED
                    && apiEx.getErrorCode() != ErrorCode.GRAPH_FORBIDDEN;
        }
        return true;
    }

    private String describeFailure(RuntimeException e) {
        String message = e.getMessage();
        return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
    }

    /** 호출될 때마다 새 업로드 세션을 만든다 — 재시도가 항상 이 메서드를 다시 부르는 것만으로 "세션 재생성"이 된다. */
    private String uploadFile(String driveId, String folderItemId, String fileName, Path tarPath, long fileSize) {
        String uploadUrl = createUploadSession(driveId, folderItemId, fileName);
        long offset = 0;
        int rangeMismatchRetries = 0;
        try (RandomAccessFile file = new RandomAccessFile(tarPath.toFile(), "r")) {
            while (offset < fileSize) {
                long end = Math.min(offset + chunkSize, fileSize) - 1;
                byte[] chunk = readChunk(file, offset, end);
                GraphApiClient.ChunkUploadResult result = putChunkWithRetry(uploadUrl, chunk, offset, end, fileSize);

                if (result.statusCode() == 416) {
                    rangeMismatchRetries++;
                    if (rangeMismatchRetries > MAX_RANGE_MISMATCH_RETRIES) {
                        throw new IllegalStateException("E-1103: 업로드 범위가 반복해서 어긋납니다: " + fileName);
                    }
                    offset = refreshOffset(uploadUrl, offset);
                    continue;
                }
                if (result.statusCode() == 404 || result.statusCode() == 410) {
                    throw new IllegalStateException("E-1102: 업로드 세션이 소멸했습니다: " + fileName);
                }
                if (!result.success()) {
                    throw new IllegalStateException(
                            "E-1101: 업로드가 실패했습니다(status=%d): %s".formatted(result.statusCode(), fileName));
                }

                rangeMismatchRetries = 0;
                offset = end + 1;
                if (offset >= fileSize) {
                    return extractWebUrl(result.body());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("E-1102: 업로드 대상 파일을 읽을 수 없습니다: " + tarPath.getFileName(), e);
        }
        throw new IllegalStateException("E-1102: 업로드가 완료되지 않았습니다: " + fileName);
    }

    private String createUploadSession(String driveId, String folderItemId, String fileName) {
        Map<String, Object> body =
                Map.of("item", Map.of("@microsoft.graph.conflictBehavior", "replace", "name", fileName));
        String response = graphApiClient.post(
                "/drives/%s/items/%s:/%s:/createUploadSession".formatted(driveId, folderItemId, fileName), body);
        return extractUploadUrl(response);
    }

    /** E-1101(5xx)/E-1106(429) — 같은 청크를 그대로 재전송한다. 429는 서버가 명시한 Retry-After를 우선한다. */
    private GraphApiClient.ChunkUploadResult putChunkWithRetry(String uploadUrl, byte[] chunk, long start, long end, long total) {
        int attempt = 0;
        while (true) {
            GraphApiClient.ChunkUploadResult result = graphApiClient.putChunk(uploadUrl, chunk, start, end, total);
            int status = result.statusCode();
            boolean retryable = status == 429 || status >= 500;
            if (!retryable || attempt >= retryProperties.maxRetries()) {
                return result;
            }
            attempt++;
            sleep(result.retryAfter() != null ? result.retryAfter() : retryProperties.backoffFor(attempt));
        }
    }

    private long refreshOffset(String uploadUrl, long currentOffset) {
        String status;
        try {
            status = graphApiClient.getUploadSessionStatus(uploadUrl);
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();
            if (code == 404 || code == 410) {
                throw new IllegalStateException("E-1102: 업로드 세션이 소멸했습니다.");
            }
            throw ex;
        }
        try {
            JsonNode ranges = objectMapper.readTree(status).path("nextExpectedRanges");
            if (ranges.isArray() && !ranges.isEmpty()) {
                return Long.parseLong(ranges.get(0).asText().split("-")[0]);
            }
        } catch (JsonProcessingException | NumberFormatException e) {
            log.warn("업로드 세션 상태 파싱에 실패했습니다.", e);
        }
        return currentOffset;
    }

    private byte[] readChunk(RandomAccessFile file, long start, long end) throws IOException {
        byte[] buffer = new byte[(int) (end - start + 1)];
        file.seek(start);
        file.readFully(buffer);
        return buffer;
    }

    private String extractUploadUrl(String json) {
        return extractRequiredField(json, "uploadUrl", "Graph 업로드 세션 응답");
    }

    private String extractWebUrl(String json) {
        return extractRequiredField(json, "webUrl", "Graph 업로드 완료 응답");
    }

    private String extractRequiredField(String json, String field, String context) {
        try {
            String value = objectMapper.readTree(json).path(field).asText(null);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(context + "에 " + field + "이(가) 없습니다.");
            }
            return value;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(context + " 파싱에 실패했습니다.", e);
        }
    }

    private boolean failItem(PackageItem item, String dbMessage, String detail) {
        log.warn(
                "업로드 항목 실패: versionName={}, imageTag={}, reason={}, detail={}",
                item.getVersionName(),
                item.getImageTag(),
                dbMessage,
                detail);
        item.markFailed(dbMessage);
        packageItemRepository.save(item);
        return false;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재시도 대기 중 인터럽트되었습니다.", e);
        }
    }
}
