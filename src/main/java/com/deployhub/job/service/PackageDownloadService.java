package com.deployhub.job.service;

import com.deployhub.common.BoundedParallelism;
import com.deployhub.common.CredentialMasker;
import com.deployhub.common.retry.RetryProperties;
import com.deployhub.job.entity.PackageItem;
import com.deployhub.job.entity.PackageItemStatus;
import com.deployhub.job.repository.PackageItemRepository;
import com.deployhub.registry.ImageReference;
import com.deployhub.registry.NcrProperties;
import com.deployhub.registry.NcrRegistryClient;
import com.deployhub.registry.NcrRegistryClient.ManifestInfo;
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
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * FN-06-1 다운로드 + FN-07 재시도 (구현계획서 466-490행, DOWNLOADING 단계). {@code
 * manifestContext}가 비어 있으면(FN-07 수동 재시도 재개 경로 — VALIDATING을 다시 안 돈다)
 * 각 항목을 받기 직전에 매니페스트를 그 자리에서 다시 조회한다 — 전체 재검증(E-0501/E-0502
 * job 중단 판정 포함)은 하지 않고, digest 대조 기준값만 새로 얻는다.
 */
@Slf4j
@Service
public class PackageDownloadService {

    // 매니페스트 layers[].size는 압축된 blob 크기지만 docker-archive: 목적지는 압축 해제된
    // 레이어를 쓴다 — 실측 압축비가 보통 2~3배라 여유를 넉넉히 잡는다(코드리뷰로 발견,
    // 1.5배는 통과시키고 실제 다운로드에서 디스크가 터지는 경우가 있었음).
    private static final double REQUIRED_FREE_SPACE_RATIO = 3.0;
    private static final Pattern NON_RETRYABLE_STDERR =
            Pattern.compile("(?i)unauthorized|forbidden|\\b401\\b|\\b403\\b|\\b404\\b|manifest unknown|not found");
    private static final int STDERR_CAPTURE_LIMIT = 8192;
    private static final ObjectMapper AUTH_FILE_MAPPER = new ObjectMapper();

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
        // FAILED는 여기서 대상에 넣지 않는다 — 넣으면 FN-07의 "지정한 태그만 재시도"가
        // 무력화된다(PackageJobService.resolveRetryTargets가 선택 안 된 FAILED 항목은
        // PENDING으로 안 돌려놓는데, 여기서 FAILED까지 주워버리면 결국 전부 재시도된다).
        // 최초 실행(start())에서는 애초에 PENDING만 존재하므로 영향 없다.
        List<PackageItem> targets = packageItemRepository.findByVersionNameOrderByImageTagAsc(versionName).stream()
                .filter(item -> item.getStatus() == PackageItemStatus.PENDING)
                .toList();
        if (targets.isEmpty()) {
            return; // 전 항목 DOWNLOADED (재시도 재개 시 이미 끝난 케이스)
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

    // manifestContext가 비어 있는 재시도 경로는 예상 크기를 미리 알 수 없어 이 사전 확인을
    // 건너뛴다 — 항목별 skopeo 실행이 어차피 그 시점의 실제 공간 부족을 실패로 드러낸다.
    private void checkDiskSpace(Path imagesDir, Map<String, ManifestInfo> manifestContext, List<PackageItem> targets) {
        if (manifestContext.isEmpty()) {
            return;
        }
        long expectedTotal = targets.stream()
                .map(item -> manifestContext.get(item.getImageTag()))
                .filter(Objects::nonNull)
                .mapToLong(ManifestInfo::totalSize)
                .sum();
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
            // PackageValidationService.checkItem과 동일한 정책 — 형식 오류는 항목 실패로
            // 국한한다. 재시도(resume) 경로는 VALIDATING을 건너뛰므로 이 방어가 실제로 쓰인다.
            return failItem(item, "E-0501: image_tag 형식이 올바르지 않습니다.", e.getMessage());
        }

        ManifestInfo expected = manifestInfo != null ? manifestInfo : fetchManifestSafely(ref, item.getImageTag());
        if (expected == null) {
            return failItem(item, "E-0501: 이미지가 존재하지 않습니다.", null);
        }

        Path tarPath = imagesDir.resolve(ref.tarFileName());

        int attempt = 0;
        while (true) {
            // docker-archive: 목적지는 기존 파일 수정을 지원하지 않는다("doesn't support
            // modifying existing images") — 이전 시도(또는 이전 크래시)가 남긴 부분/완성
            // tar가 있으면 재시도가 매번 이 오류로 즉시 실패해 사실상 재시도가 안 된다.
            // 매 시도 전에 지운다(구현계획서 488행 "부분 파일 삭제 후 재다운로드").
            deleteQuietly(tarPath);
            SkopeoResult result = runSkopeo(ref, tarPath, authFile);
            if (result.exitCode() == 0) {
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
            sleep(retryProperties.backoffFor(attempt));
        }
    }

    /**
     * digest 대조는 {@code --digestfile}이 아니라 다운로드 직후 매니페스트를 다시 REST로
     * 조회해서 한다 — {@code docker-archive:} 목적지는 Docker v2 schema2만 받아들여서,
     * 원본이 OCI manifest(최신 skopeo/buildx의 기본값, 실측으로 확인)면 skopeo가 강제
     * 변환하고 {@code --digestfile}엔 변환된(목적지) digest가 찍힌다 — FN-05가 REST로 관찰한
     * 원본 digest와는 애초에 다른 값이라 비교 자체가 성립하지 않는다(항상 불일치로 오탐).
     * 대신 같은 REST 경로로 "확정 시점"과 "다운로드 직후" 두 시점의 원본 digest를 비교해
     * 재푸시 여부(TOCTOU)를 확인한다 — 스펙 의도(동일 태그 재푸시 탐지)는 그대로 지키면서
     * 목적지 포맷 변환과 무관해진다.
     *
     * <p>digest가 하나라도 null이면(레지스트리가 헤더를 안 주는 경우 등) 무조건 실패로
     * 처리한다 — null == null로 "일치" 판정이 나면 무결성 검사가 fail-open이 된다.
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
     * DB에 남기는 {@code dbMessage}에는 서버 경로·레지스트리 호스트 같은 인프라 정보를
     * 절대 넣지 않는다 — 인증 없는 {@code GET /api/package-jobs/{versionName}}가 이
     * 값을 그대로 응답에 싣는다(PackageJobService.checkDiskSpace의 동일 정책 참고).
     * 상세(stderr, 예외 메시지 등)는 {@code detail}로 받아 로그에만 남긴다.
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

    /** 재시도 재개(컨텍스트 없음) 시의 즉석 조회와, 다운로드 직후 digest 재확인이 함께 쓴다. */
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
        command.add("docker-archive:%s:%s:%s".formatted(tarPath, ref.repository(), ref.tag()));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        // skopeo가 표준입력을 안 쓰지만 기본값은 파이프다 — 우리가 닫지 않으면 프로세스가
        // 끝나도 그 파이프 파일 디스크립터가 GC될 때까지 남는다.
        pb.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        try {
            Process process = pb.start();
            // StringBuilder는 스레드 안전하지 않다 — join()이 타임아웃해 리더 스레드가
            // 아직 쓰고 있는 도중 메인 스레드가 toString()을 읽으면 깨진 문자열이나 예외가
            // 날 수 있다. StringBuffer로 동기화한다.
            StringBuffer stderrBuffer = new StringBuffer();
            // stderr 파이프가 OS 버퍼(보통 64KB)를 채우면 자식 프로세스가 write()에서
            // 블로킹된다 — waitFor(timeout)만 쓰면 실제로는 멈추지 않은 프로세스가 파이프
            // 역압 때문에 타임아웃으로 오판될 수 있어, 대기와 동시에 별도 스레드로 비운다.
            // 데몬 스레드로 띄운다 — join이 타임아웃해도 JVM 종료를 막지 않는다.
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
                    // 프로세스 종료로 스트림이 닫히면서 발생하는 경우가 대부분 — 무시한다.
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
            // E-0605 — StartupChecks가 조기 검출하지만, 기동 이후 경로가 사라지는 경우를 방어한다.
            return new SkopeoResult(-1, "E-0605: skopeo를 실행할 수 없습니다: " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("다운로드 대기 중 인터럽트되었습니다.", e);
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
                // POSIX 권한을 지원하지 않는 파일시스템(Windows 로컬 실행 등) — 운영은
                // Linux 서버 전제라 권한 제한 없이 진행한다. 여기서 안 잡으면 로컬 개발
                // 실행 자체가 즉시 죽는다.
                log.warn("파일 권한 설정을 지원하지 않는 파일시스템입니다 — 권한 제한 없이 진행합니다: {}", authFile);
            }
            // 자격 증명에 따옴표·역슬래시가 섞여도 깨지지 않도록 문자열 조립 대신 Jackson으로 직렬화한다.
            Files.writeString(authFile, AUTH_FILE_MAPPER.writeValueAsString(content));
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

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재시도 대기 중 인터럽트되었습니다.", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("파일 삭제 실패: {}", path, e);
        }
    }

    private record SkopeoResult(int exitCode, String stderr, boolean timedOut) {}

    /** authfile 경로 + 그 안에 든 base64(accessKey:secretKey) — stderr 마스킹 대상으로도 쓴다. */
    private record AuthFile(Path path, String base64Value) {}
}
