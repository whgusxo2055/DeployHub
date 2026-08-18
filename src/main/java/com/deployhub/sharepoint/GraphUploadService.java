package com.deployhub.sharepoint;

import com.deployhub.common.ApiException;
import com.deployhub.common.ItemErrorCode;
import com.deployhub.common.ErrorCode;
import com.deployhub.common.retry.RetryExecutor;
import com.deployhub.common.retry.RetryProperties;
import com.deployhub.common.retry.RetryableCallException;
import com.deployhub.job.entity.PackageItem;
import com.deployhub.job.entity.PackageItemStatus;
import com.deployhub.job.repository.PackageItemRepository;
import com.deployhub.job.service.PackageItemFailure;
import com.deployhub.registry.ImageReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

/**
 * 파일별 순차 업로드. 산출물이 전량 GB 단위 이미지 tar라 업로드 세션 경로만 쓴다(단순 PUT은 다루지 않는다).
 * 세션이 소멸하면 이어받기가 불가능해 바깥 재시도 루프가 {@link #uploadFile}을 처음부터 다시 호출한다 —
 * 범위 불일치(416)와 청크 전송 타임아웃만 세션을 유지한 채 이어간다.
 */
@Slf4j
@Service
public class GraphUploadService {

    // 416 무한루프 방지. 청크가 성공할 때마다 0으로 되돌릴 것 — 아니면 큰 파일에서 서로 다른
    // 지점의 일시적 416이 누적돼 파일 전체가 실패한다.
    // ponytail: 실 테넌트 없이 검증할 수 없어 보수적으로 작게 잡았다 — Graph 연동 후 재검증할 것.
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
     * 이미 {@code UPLOADED}인 항목도 <b>다시</b> 올린다 — {@link GraphFolderService#ensureFolder}가
     * 재사용 시 폴더를 비우므로, 건너뛰면 그 파일이 폴더에서 사라진 채로 남는다.
     * 로컬 tar는 Job이 DONE에 닿기 전까지 정리 배치가 지우지 않아 항상 남아 있다.
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
            return failItem(item, ItemErrorCode.INVALID_IMAGE_TAG, e.getMessage());
        }
        String fileName = ref.tarFileName();
        Path tarPath = Path.of(workDir, item.getVersionName(), "images", fileName);
        long fileSize;
        try {
            fileSize = Files.size(tarPath);
        } catch (IOException e) {
            return failItem(item, ItemErrorCode.UPLOAD_FILE_MISSING, e.getMessage());
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
                    return failItem(item, classifyFailure(e), e.toString());
                }
                item.incrementRetryCount();
                packageItemRepository.save(item);
                RetryExecutor.sleepOrThrowOnInterrupt(retryProperties.backoffFor(attempt));
            }
        }
    }

    /** 토큰 발급 실패·권한 부족은 재시도해도 결과가 안 바뀐다 — 예산을 태우지 않고 바로 실패시킨다. */
    private boolean isRetryable(RuntimeException e) {
        if (e instanceof ApiException apiEx) {
            return apiEx.getErrorCode() != ErrorCode.GRAPH_TOKEN_ISSUE_FAILED
                    && apiEx.getErrorCode() != ErrorCode.GRAPH_FORBIDDEN;
        }
        return true;
    }

    /**
     * DB에 남길 사유를 코드로 고른다. 예외 메시지를 그대로 쓰지 않는다 —
     * {@code RestClientResponseException}의 메시지에는 Graph 응답 본문이 통째로 들어 있고,
     * 그게 무인증 {@code GET /api/package-jobs/{versionName}} 응답으로 나간다. 원문은 호출자가
     * {@code detail}로 넘겨 로그에만 남긴다.
     */
    private ItemErrorCode classifyFailure(RuntimeException e) {
        // 청크 재시도를 소진하고 올라온 타임아웃·5xx는 껍데기가 RetryableCallException이다 —
        // 벗기지 않으면 Graph 장애가 UPLOAD_UNAVAILABLE이 아니라 UPLOAD_FAILED로 기록된다.
        if (e instanceof RetryableCallException retryable) {
            return classifyFailure(retryable.giveUpException());
        }
        if (e instanceof ApiException apiEx) {
            return switch (apiEx.getErrorCode()) {
                case GRAPH_TOKEN_ISSUE_FAILED -> ItemErrorCode.UPLOAD_TOKEN_FAILED;
                case GRAPH_FORBIDDEN -> ItemErrorCode.UPLOAD_FORBIDDEN;
                case GRAPH_UNAVAILABLE -> ItemErrorCode.UPLOAD_UNAVAILABLE;
                default -> ItemErrorCode.UPLOAD_FAILED;
            };
        }
        return ItemErrorCode.UPLOAD_FAILED;
    }

    /** 호출될 때마다 새 세션을 만든다 — 재시도가 이 메서드를 다시 부르는 것만으로 세션 재생성이 된다. */
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

    /** 5xx·429는 같은 청크를 그대로 재전송한다. 429는 서버가 명시한 Retry-After를 우선한다. */
    private GraphApiClient.ChunkUploadResult putChunkWithRetry(String uploadUrl, byte[] chunk, long start, long end, long total) {
        int attempt = 0;
        while (true) {
            GraphApiClient.ChunkUploadResult result;
            try {
                result = graphApiClient.putChunk(uploadUrl, chunk, start, end, total);
            } catch (RetryableCallException e) {
                // 타임아웃을 그대로 올리면 uploadItemWithRetry가 새 세션으로 파일을 처음부터 다시 올린다 —
                // GB 단위 tar에서는 수렴하지 않는다. 같은 Range 재전송은 안전하다(서버가 이미 받았다면
                // 416으로 답하고 uploadFile의 재개 경로가 오프셋을 맞춘다).
                if (attempt >= retryProperties.maxRetries()) {
                    throw e;
                }
                attempt++;
                RetryExecutor.sleepOrThrowOnInterrupt(retryProperties.backoffFor(attempt));
                continue;
            }
            int status = result.statusCode();
            boolean retryable = status == 429 || status >= 500;
            if (!retryable || attempt >= retryProperties.maxRetries()) {
                return result;
            }
            attempt++;
            RetryExecutor.sleepOrThrowOnInterrupt(
                    result.retryAfter() != null ? result.retryAfter() : retryProperties.backoffFor(attempt));
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

    private boolean failItem(PackageItem item, ItemErrorCode errorCode, String detail) {
        return PackageItemFailure.fail(packageItemRepository, item, errorCode, detail);
    }

}
