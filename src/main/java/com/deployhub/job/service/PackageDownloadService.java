package com.deployhub.job.service;

import com.deployhub.common.BoundedParallelism;
import com.deployhub.common.CredentialMasker;
import com.deployhub.common.retry.RetryExecutor;
import com.deployhub.common.retry.RetryProperties;
import com.deployhub.job.entity.PackageItem;
import com.deployhub.job.entity.PackageItemStatus;
import com.deployhub.job.repository.PackageItemRepository;
import com.deployhub.registry.ImageReference;
import com.deployhub.registry.NcrProperties;
import com.deployhub.registry.NcrRegistryClient;
import com.deployhub.registry.NcrRegistryClient.ManifestInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * DOWNLOADING 단계 — skopeo로 이미지를 받아 반입용 아카이브로 저장한다.
 * {@code manifestContext}가 비면(수동 재시도 재개 경로) 항목마다 digest 기준값만 즉석에서 다시 조회한다.
 */
@Slf4j
@Service
public class PackageDownloadService {

    // oci-archive:는 압축 레이어를 그대로 담아 산출 tar가 layers[].size 합계와 거의 같다(+0.2%).
    private static final double REQUIRED_FREE_SPACE_RATIO = 1.2;
    /** 아카이브에서 읽은 digest를 blob 경로로 조립하기 전 형식 확인(NCR·skopeo 모두 sha256만 쓴다). */
    private static final Pattern BLOB_DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");
    // "no space left"가 빠지면 디스크가 찬 상태에서 수 GB 재다운로드를 maxRetries만큼 반복한다.
    private static final Pattern NON_RETRYABLE_STDERR = Pattern.compile(
            "(?i)unauthorized|forbidden|\\b401\\b|\\b403\\b|\\b404\\b|manifest unknown|not found|no space left");
    private static final int STDERR_CAPTURE_LIMIT = 8192;
    // Boot 빈이 아니라 기본 설정 매퍼 — 아카이브 JSON만 다뤄 Spring 컨텍스트 설정과 무관해야 한다.
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final PackageItemRepository packageItemRepository;
    private final NcrRegistryClient ncrRegistryClient;
    private final NcrProperties ncrProperties;
    private final RetryProperties retryProperties;
    private final Executor downloadExecutor;
    private final String workDir;
    private final int downloadConcurrency;
    private final int skopeoTimeoutSeconds;

    public PackageDownloadService(
            PackageItemRepository packageItemRepository,
            NcrRegistryClient ncrRegistryClient,
            NcrProperties ncrProperties,
            RetryProperties retryProperties,
            @Qualifier("downloadExecutor") Executor downloadExecutor,
            @Value("${deployhub.work-dir}") String workDir,
            @Value("${deployhub.download.concurrency:3}") int downloadConcurrency,
            @Value("${deployhub.download.skopeo-timeout:1800}") int skopeoTimeoutSeconds) {
        this.packageItemRepository = packageItemRepository;
        this.ncrRegistryClient = ncrRegistryClient;
        this.ncrProperties = ncrProperties;
        this.retryProperties = retryProperties;
        this.downloadExecutor = downloadExecutor;
        this.workDir = workDir;
        this.downloadConcurrency = downloadConcurrency;
        this.skopeoTimeoutSeconds = skopeoTimeoutSeconds;
    }

    public void download(String versionName, Map<String, ManifestInfo> manifestContext) {
        // PENDING만 대상이다 — FAILED까지 주우면 "지정한 태그만 재시도"가 무력화된다
        // (선택 안 된 FAILED 항목은 PENDING으로 되돌려지지 않는다).
        List<PackageItem> targets = packageItemRepository.findByVersionNameOrderByImageTagAsc(versionName).stream()
                .filter(item -> item.getStatus() == PackageItemStatus.PENDING)
                .toList();
        if (targets.isEmpty()) {
            return; // 전 항목 DOWNLOADED
        }

        Path imagesDir = Path.of(workDir, versionName, "images");
        try {
            Files.createDirectories(imagesDir);
        } catch (IOException e) {
            throw new IllegalStateException("E-0602: 작업 디렉터리를 만들 수 없습니다: " + imagesDir, e);
        }
        checkDiskSpace(imagesDir, manifestContext, targets);

        AuthFile authFile = writeAuthFile();
        try {
            List<Boolean> results = BoundedParallelism.mapInBatches(
                    targets,
                    downloadConcurrency,
                    downloadExecutor,
                    item -> downloadItemWithRetry(item, manifestContext.get(item.getImageTag()), imagesDir, authFile));
            if (results.contains(Boolean.FALSE)) {
                throw new IllegalStateException("일부 항목 다운로드에 실패했습니다.");
            }
        } finally {
            deleteQuietly(authFile.path());
        }
    }

    // 예상 크기를 모르는 경로(재시도 재개)는 건너뛴다 — skopeo 실행이 어차피 실제 부족을 드러낸다.
    private void checkDiskSpace(Path imagesDir, Map<String, ManifestInfo> manifestContext, List<PackageItem> targets) {
        if (manifestContext.isEmpty()) {
            return;
        }
        List<ManifestInfo> infos = targets.stream()
                .map(item -> manifestContext.get(item.getImageTag()))
                .filter(Objects::nonNull)
                .toList();
        // 미상이 하나라도 섞이면 합계가 과소평가돼 가드가 조용히 통과한다 — 아예 건너뛴다.
        if (infos.stream().anyMatch(ManifestInfo::hasUnknownSize)) {
            log.warn("예상 크기를 알 수 없는 항목이 있어 디스크 사전 확인을 건너뜁니다.");
            return;
        }
        long expectedTotal = infos.stream().mapToLong(ManifestInfo::totalSize).sum();
        long required = (long) (expectedTotal * REQUIRED_FREE_SPACE_RATIO);
        long usable = imagesDir.toFile().getUsableSpace();
        if (usable < required) {
            log.warn("E-0602 디스크 여유 공간 부족: required={} bytes, usable={} bytes", required, usable);
            throw new IllegalStateException("E-0602: 디스크 여유 공간이 부족합니다.");
        }
    }

    private boolean downloadItemWithRetry(PackageItem item, ManifestInfo manifestInfo, Path imagesDir, AuthFile authFile) {
        ImageReference ref;
        try {
            ref = ImageReference.parse(item.getImageTag());
        } catch (IllegalArgumentException e) {
            // 형식 오류는 항목 실패로 국한한다. 재시도 경로는 VALIDATING을 건너뛰어 이 방어가 실제로 쓰인다.
            return failItem(item, "E-0501: image_tag 형식이 올바르지 않습니다.", e.getMessage());
        }

        ManifestInfo expected = manifestInfo != null ? manifestInfo : fetchManifestSafely(ref, item.getImageTag());
        if (expected == null) {
            return failItem(item, "E-0501: 이미지가 존재하지 않습니다.", null);
        }

        Path tarPath = imagesDir.resolve(ref.tarFileName());

        int attempt = 0;
        while (true) {
            // 아카이브 목적지는 기존 파일 수정을 지원하지 않는다 — 남은 tar가 있으면 재시도가
            // 매번 즉시 실패한다. 마지막 실패 시점이 아니라 매 시도 시작에 지워야 한다.
            deleteQuietly(tarPath);
            SkopeoResult result = runSkopeo(ref, tarPath, authFile);
            if (result.exitCode() == 0) {
                // 후처리 실패는 조립 로직 문제라 재시도해도 같다 — 다시 받지 않고 항목 실패로 끝낸다.
                String postProcessFailure = appendLegacyManifest(ref, tarPath);
                if (postProcessFailure != null) {
                    deleteQuietly(tarPath);
                    return failItem(item, "E-0604: 다운로드 파일 후처리에 실패했습니다.", postProcessFailure);
                }
                return handleSuccess(item, expected, ref, tarPath);
            }

            String maskedStderr =
                    CredentialMasker.mask(result.stderr(), ncrProperties.accessKey(), ncrProperties.secretKey(), authFile.base64Value());
            boolean retryable = isRetryable(maskedStderr);
            if (!retryable || attempt >= retryProperties.maxRetries()) {
                deleteQuietly(tarPath);
                String prefix = result.timedOut() ? "E-0606" : "E-0601";
                return failItem(
                        item, "%s: skopeo 실행 실패(exit=%d)".formatted(prefix, result.exitCode()), maskedStderr);
            }
            attempt++;
            item.incrementRetryCount();
            packageItemRepository.save(item);
            RetryExecutor.sleepUninterruptibly(retryProperties.backoffFor(attempt));
        }
    }

    /**
     * digest 대조는 아카이브 안 값이 아니라 REST로 재조회한 값으로 한다 — 태그가 인덱스를 가리키면
     * skopeo가 플랫폼 하나로 평탄화해 담아 아카이브 digest는 애초에 다른 값이다(항상 오탐).
     * digest가 하나라도 null이면 실패로 처리한다 — null==null 일치 판정은 fail-open이다.
     */
    private boolean handleSuccess(PackageItem item, ManifestInfo expected, ImageReference ref, Path tarPath) {
        ManifestInfo current = fetchManifestSafely(ref, item.getImageTag());
        String expectedDigest = expected.digest();
        String currentDigest = current == null ? null : current.digest();
        boolean digestOk = expectedDigest != null
                && currentDigest != null
                && normalizeDigest(currentDigest).equals(normalizeDigest(expectedDigest));
        if (!digestOk) {
            deleteQuietly(tarPath);
            return failItem(
                    item,
                    "E-0603: 다운로드된 이미지의 digest가 확정 시점과 다릅니다(재푸시 의심). 자동 재시도 대상이 아닙니다.",
                    "expected=%s, current=%s".formatted(expectedDigest, currentDigest));
        }

        long fileSize;
        try {
            fileSize = Files.size(tarPath);
        } catch (IOException e) {
            deleteQuietly(tarPath);
            return failItem(item, "E-0604: 다운로드 파일을 확인할 수 없습니다.", e.getMessage());
        }
        if (fileSize == 0) {
            deleteQuietly(tarPath);
            return failItem(item, "E-0604: 다운로드 파일 크기가 0입니다.", null);
        }

        item.markDownloaded(fileSize);
        packageItemRepository.save(item);
        return true;
    }

    /**
     * {@code dbMessage}에 서버 경로·호스트 같은 인프라 정보를 넣지 말 것 — 무인증 조회 API가
     * 이 값을 그대로 응답에 싣는다. 상세(stderr 등)는 {@code detail}로 받아 로그에만 남긴다.
     */
    private boolean failItem(PackageItem item, String dbMessage, String detail) {
        log.warn(
                "항목 실패: versionName={}, imageTag={}, reason={}, detail={}",
                item.getVersionName(),
                item.getImageTag(),
                dbMessage,
                detail);
        item.markFailed(dbMessage);
        packageItemRepository.save(item);
        return false;
    }

    /** 재시도 재개 시의 즉석 조회와 다운로드 직후 digest 재확인이 함께 쓴다. */
    private ManifestInfo fetchManifestSafely(ImageReference ref, String imageTagForLog) {
        try {
            return ncrRegistryClient.getManifest(ref.repository(), ref.tag()).orElse(null);
        } catch (RuntimeException e) {
            log.warn("매니페스트 재조회에 실패했습니다: {}", imageTagForLog, e);
            return null;
        }
    }

    private SkopeoResult runSkopeo(ImageReference ref, Path tarPath, AuthFile authFile) {
        String host = stripScheme(ncrProperties.endpoint());
        List<String> command = new ArrayList<>();
        command.add(ncrProperties.cliPath());
        command.add("copy");
        command.add("--authfile");
        command.add(authFile.path().toString());
        if (isPlainHttp(ncrProperties.endpoint())) {
            command.add("--src-tls-verify=false");
        }
        command.add("docker://%s/%s:%s".formatted(host, ref.repository(), ref.tag()));
        // oci-archive:는 레이어 압축을 보존한다(docker-archive:는 전부 풀어 담아 산출물이 몇 배로 불어난다).
        // 목적지 참조는 "저장소:태그" 전체를 넘길 것 — 이 값이 index.json의 ref.name이 되고
        // Docker 29가 태그를 거기서 복원한다(태그만 넘기면 "v1.0.0:latest"로 적재된다).
        command.add("oci-archive:%s:%s:%s".formatted(tarPath, ref.repository(), ref.tag()));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        // 기본값이 파이프라 닫지 않으면 프로세스가 끝나도 파일 디스크립터가 GC까지 남는다.
        pb.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        try {
            Process process = pb.start();
            // StringBuilder가 아니라 StringBuffer — join() 타임아웃 시 리더 스레드가 쓰는 도중 읽게 된다.
            StringBuffer stderrBuffer = new StringBuffer();
            // stderr 파이프가 OS 버퍼를 채우면 자식이 write()에서 막혀 타임아웃으로 오판된다 —
            // 대기와 동시에 데몬 스레드로 비운다.
            Thread stderrReader = new Thread(() -> {
                try (BufferedReader reader =
                        new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (stderrBuffer.length() < STDERR_CAPTURE_LIMIT) {
                            stderrBuffer.append(line).append('\n');
                        }
                    }
                } catch (IOException ignored) {
                    // 프로세스 종료로 스트림이 닫히는 경우가 대부분 — 무시한다.
                }
            });
            stderrReader.setDaemon(true);
            stderrReader.start();
            boolean finished = process.waitFor(skopeoTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            stderrReader.join(Duration.ofSeconds(5).toMillis());
            int exitCode = finished ? process.exitValue() : -1;
            return new SkopeoResult(exitCode, stderrBuffer.toString(), !finished);
        } catch (IOException e) {
            // StartupChecks가 조기 검출하지만 기동 이후 경로가 사라지는 경우를 방어한다.
            return new SkopeoResult(-1, "E-0605: skopeo를 실행할 수 없습니다: " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("다운로드 대기 중 인터럽트되었습니다.", e);
        }
    }

    /**
     * OCI 아카이브에 레거시 {@code manifest.json}을 덧붙인다 — Docker 28 이하는 이게 없으면 적재를 못 하고
     * 태그도 여기 {@code RepoTags}로만 복원한다(29는 {@code index.json}의 ref.name을 본다).
     * 풀었다 다시 묶지 않고 {@code tar -r}로 덧붙인다 — 10GB급에서 재압축은 디스크를 두 배로 쓴다.
     *
     * @return 실패 사유, 성공이면 {@code null}
     */
    private String appendLegacyManifest(ImageReference ref, Path tarPath) {
        Path workDirectory = null;
        try {
            // skopeo copy는 인덱스도 플랫폼 하나로 평탄화하므로 manifests[0]은 항상 이미지 매니페스트다.
            JsonNode index = JSON_MAPPER.readTree(readArchiveEntry(tarPath, "index.json"));
            JsonNode manifest = JSON_MAPPER.readTree(
                    readArchiveEntry(tarPath, blobPath(index.path("manifests").path(0).path("digest").asText())));
            List<String> layers = new ArrayList<>();
            for (JsonNode layer : manifest.path("layers")) {
                // oci-archive:는 원본 압축을 보존한다 — zstd 레이어는 Docker 28 이하에서만 실패해
                // 최신 데몬으로는 못 잡는다. 고객사에서 터지기 전에 여기서 끊는다.
                if (layer.path("mediaType").asText().contains("zstd")) {
                    return "지원하지 않는 레이어 압축(zstd)입니다 — Docker 28 이하가 적재하지 못합니다.";
                }
                layers.add(blobPath(layer.path("digest").asText()));
            }
            if (layers.isEmpty()) {
                return "아카이브 매니페스트에 레이어가 없습니다.";
            }
            Map<String, Object> entry = Map.of(
                    "Config", blobPath(manifest.path("config").path("digest").asText()),
                    "RepoTags", List.of(ref.repository() + ":" + ref.tag()),
                    "Layers", layers);
            workDirectory = Files.createTempDirectory("deployhub-manifest-");
            Files.writeString(
                    workDirectory.resolve("manifest.json"), JSON_MAPPER.writeValueAsString(List.of(entry)));
            ProcessResult appended = runProcess(
                    List.of("tar", "-rf", tarPath.toString(), "-C", workDirectory.toString(), "manifest.json"), false);
            if (appended.exitCode() != 0) {
                return "tar 덧붙이기 실패(exit=%d): %s".formatted(appended.exitCode(), appended.text());
            }
            return null;
        } catch (IOException | ArchiveException e) {
            // 인터럽트는 runProcess가 다른 타입으로 던져 여기 안 걸린다 — 걸리면 셧다운이 "항목 영구 FAILED"로 굳는다.
            return e.getMessage() != null ? e.getMessage() : e.toString();
        } finally {
            if (workDirectory != null) {
                deleteQuietly(workDirectory.resolve("manifest.json"));
                deleteQuietly(workDirectory);
            }
        }
    }

    private byte[] readArchiveEntry(Path tarPath, String entryName) {
        // ponytail: skopeo가 index.json을 끝에서 두 번째로 쓰기 때문에 --occurrence=1이어도
        // 사실상 끝까지 훑는다 — 후처리가 아카이브 크기의 약 2배를 읽는다.
        ProcessResult result =
                runProcess(List.of("tar", "-xf", tarPath.toString(), "-O", "--occurrence=1", entryName), true);
        if (result.exitCode() != 0 || result.output().length == 0) {
            // exit=-1이면 tar 자체를 실행 못 한 경우로, 사유가 output에 들어 있다.
            throw new ArchiveException(
                    "아카이브에서 %s를 읽지 못했습니다(exit=%d). %s"
                            .formatted(entryName, result.exitCode(), result.text()));
        }
        return result.output();
    }

    private static String blobPath(String digest) {
        if (!BLOB_DIGEST.matcher(digest).matches()) {
            // 정상이면 걸릴 일이 없다 — 걸리면 형식이 바뀐 것이라 tar 인자로 넘기지 않고 끊는다.
            throw new ArchiveException("아카이브의 digest 형식이 올바르지 않습니다.");
        }
        return "blobs/sha256/" + digest.substring("sha256:".length());
    }

    /**
     * tar처럼 출력이 작은 보조 프로세스 전용. 출력을 별도 스레드로 비우면서 대기해야 타임아웃이 동작한다 —
     * 같은 스레드에서 EOF까지 읽으면 EOF가 프로세스 종료 때 와서 뒤의 {@code waitFor(timeout)}가 무력해진다.
     *
     * @param captureStdout true면 stdout만(stderr를 섞으면 JSON이 깨진다), false면 stderr를 합쳐 받는다.
     */
    private ProcessResult runProcess(List<String> command, boolean captureStdout) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        if (captureStdout) {
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        } else {
            pb.redirectErrorStream(true);
        }
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new ProcessResult(
                    -1, "%s 실행 실패: %s".formatted(command.get(0), e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
        AtomicReference<byte[]> captured = new AtomicReference<>(new byte[0]);
        Thread reader = new Thread(() -> {
            try (var stream = process.getInputStream()) {
                captured.set(stream.readAllBytes());
            } catch (IOException ignored) {
                // 강제 종료로 스트림이 닫히는 경우가 대부분 — 부분 출력은 버린다.
            }
        });
        reader.setDaemon(true);
        reader.start();
        try {
            boolean finished = process.waitFor(skopeoTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ProcessResult(-1, "%s 타임아웃".formatted(command.get(0)).getBytes(StandardCharsets.UTF_8));
            }
            reader.join(Duration.ofSeconds(5).toMillis());
            return new ProcessResult(process.exitValue(), captured.get());
        } catch (InterruptedException e) {
            // 고아 프로세스를 남기지 않는다. ArchiveException이 아닌 타입이라 항목 실패로 흡수되지 않는다.
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("아카이브 후처리 대기 중 인터럽트되었습니다.", e);
        }
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null");
    }

    private AuthFile writeAuthFile() {
        String host = stripScheme(ncrProperties.endpoint());
        String authValue = Base64.getEncoder()
                .encodeToString(
                        (ncrProperties.accessKey() + ":" + ncrProperties.secretKey()).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> content =
                Map.of("auths", Map.of(host, Map.of("auth", authValue)));
        try {
            Path authFile = Files.createTempFile("deployhub-auth-", ".json");
            try {
                Files.setPosixFilePermissions(authFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                // POSIX 권한 미지원 파일시스템(Windows 로컬) — 운영은 Linux 전제라 진행한다.
                log.warn("파일 권한 설정을 지원하지 않는 파일시스템입니다 — 권한 제한 없이 진행합니다: {}", authFile);
            }
            // 자격 증명에 따옴표·역슬래시가 섞여도 깨지지 않게 문자열 조립 대신 Jackson으로 직렬화한다.
            Files.writeString(authFile, JSON_MAPPER.writeValueAsString(content));
            return new AuthFile(authFile, authValue);
        } catch (IOException e) {
            throw new IllegalStateException("skopeo 인증 파일을 만들 수 없습니다.", e);
        }
    }

    private static boolean isRetryable(String stderr) {
        return !NON_RETRYABLE_STDERR.matcher(stderr).find();
    }

    private static String stripScheme(String endpoint) {
        return endpoint.replaceFirst("(?i)^https?://", "");
    }

    private static boolean isPlainHttp(String endpoint) {
        return endpoint.regionMatches(true, 0, "http://", 0, 7);
    }

    private static String normalizeDigest(String digest) {
        String trimmed = digest.strip();
        String withoutPrefix = trimmed.regionMatches(true, 0, "sha256:", 0, 7) ? trimmed.substring(7) : trimmed;
        return withoutPrefix.toLowerCase(Locale.ROOT);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("파일 삭제 실패: {}", path, e);
        }
    }

    private record SkopeoResult(int exitCode, String stderr, boolean timedOut) {}

    private record ProcessResult(int exitCode, byte[] output) {
        String text() {
            return new String(output, StandardCharsets.UTF_8);
        }
    }

    /** 아카이브 후처리 실패 — 항목 실패로 흡수된다. 인터럽트와 구분하려 별도 타입이다. */
    private static class ArchiveException extends RuntimeException {
        ArchiveException(String message) {
            super(message);
        }
    }

    /** authfile 경로와 그 안의 base64 자격 증명 — stderr 마스킹 대상으로도 쓴다. */
    private record AuthFile(Path path, String base64Value) {}
}
