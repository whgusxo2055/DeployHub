package com.deployhub.job.service;

import com.deployhub.common.ErrorCode;
import com.deployhub.common.Concurrency;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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
    // "no space left"가 빠지면 디스크가 찬 상태에서 수 GB 재다운로드를 maxRetries만큼 반복한다.
    private static final Pattern NON_RETRYABLE_STDERR = Pattern.compile(
            "(?i)unauthorized|forbidden|\\b401\\b|\\b403\\b|\\b404\\b|manifest unknown|not found|no space left");
    private static final int STDERR_CAPTURE_LIMIT = 8192;
    // Boot 빈이 아니라 기본 설정 매퍼 — authfile 직렬화 전용이라 Spring 컨텍스트 설정과 무관해야 한다.

    private final PackageItemRepository packageItemRepository;
    private final NcrRegistryClient ncrRegistryClient;
    private final NcrProperties ncrProperties;
    private final RetryProperties retryProperties;
    private final ObjectMapper objectMapper;
    // 동시 다운로드 수는 이 풀의 고정 크기가 정한다.
    private final ExecutorService downloadExecutor;
    private final String workDir;
    private final int skopeoTimeoutSeconds;

    public PackageDownloadService(
            PackageItemRepository packageItemRepository,
            NcrRegistryClient ncrRegistryClient,
            NcrProperties ncrProperties,
            RetryProperties retryProperties,
            ObjectMapper objectMapper,
            @Qualifier("downloadExecutor") ExecutorService downloadExecutor,
            @Value("${deployhub.work-dir}") String workDir,
            @Value("${deployhub.download.skopeo-timeout:1800}") int skopeoTimeoutSeconds) {
        this.packageItemRepository = packageItemRepository;
        this.ncrRegistryClient = ncrRegistryClient;
        this.ncrProperties = ncrProperties;
        this.retryProperties = retryProperties;
        this.objectMapper = objectMapper;
        this.downloadExecutor = downloadExecutor;
        this.workDir = workDir;
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

        // workDir/versionName/images — tar를 담을 폴더를 미리 확보한다. skopeo는 목적지 폴더가 없으면 실패한다.
        Path imagesDir = Path.of(workDir, versionName, "images");
        try {
            Files.createDirectories(imagesDir);
        } catch (IOException e) {
            throw new IllegalStateException(ErrorCode.WORK_DIR_CREATE_FAILED.toLogMessage(imagesDir), e);
        }

        checkDiskSpace(imagesDir, manifestContext, targets);

        // skopeo 인증 파일을 만든다 — 임시 파일을 만들고 권한을 rw-------로 제한한다.
        AuthFile authFile = writeAuthFile();
        try {
            // 동시 다운로드 — 실패 항목은 항목별로 기록한다. 일부 실패 시 IllegalStateException으로 Job을 FAILED로 전이시킨다.
            List<Boolean> results = Concurrency.mapAll(
                    downloadExecutor,
                    targets,
                    item -> downloadItemWithRetry(item, manifestContext.get(item.getImageTag()), imagesDir, authFile));
            if (results.contains(Boolean.FALSE)) {
                throw new IllegalStateException("일부 항목 다운로드에 실패했습니다.");
            }
        } finally {
            // 임시 인증 파일을 지운다 — skopeo는 실행 중에만 읽고 끝나면 닫는다.
            deleteQuietly(authFile.path());
        }
    }

    // 레지스트리 확인이 전건 실패하면 컨텍스트가 비어 합계를 알 수 없다 — skopeo 실행이 어차피 실제 부족을 드러낸다.
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

        // 포화 덧셈 — 항목이 각각 Long.MAX_VALUE로 포화하면 단순 합은 음수로 뒤집혀
        // 아래 비교가 무조건 통과한다(디스크 가드 fail-open). sumLayerSizes와 같은 패턴이다.
        long expectedTotal = 0L;
        for (ManifestInfo info : infos) {
            long size = info.totalSize();
            expectedTotal = expectedTotal + size < expectedTotal ? Long.MAX_VALUE : expectedTotal + size;
        }

        // 예상 합계에 여유율을 곱한 값이 실제 사용 가능 공간보다 크면 실패시킨다 — skopeo가
        // 실제로 tar를 만들 때 부족하면 exitCode=1, stderr="no space left"로 죽는다. 이 가드는 그 전에 미리 잡아 재시도 횟수를 줄인다.
        long required = (long) (expectedTotal * REQUIRED_FREE_SPACE_RATIO);
        long usable = imagesDir.toFile().getUsableSpace();
        if (usable < required) {
            log.warn("{} required={} bytes, usable={} bytes", ErrorCode.INSUFFICIENT_DISK.toMessage(), required, usable);
            throw new IllegalStateException(ErrorCode.INSUFFICIENT_DISK.toMessage());
        }
    }

    /**
     * skopeo로 이미지를 받아 tar로 저장한다. 실패 시 재시도한다 — 재시도 횟수는 항목별로 기록한다.
     * {@code manifestInfo}는 검증이 404로 확답하지 못한 항목(타임아웃·연결 실패)에서 null이다 — 그때만 즉석 재조회한다.
     */
    private boolean downloadItemWithRetry(PackageItem item, ManifestInfo manifestInfo, Path imagesDir, AuthFile authFile) {
        ImageReference ref;
        try {
            ref = ImageReference.parse(item.getImageTag());
        } catch (IllegalArgumentException e) {
            // 확정 시점 assertTargetTagsValid가 이미 거르므로 정상 경로로는 도달하지 않는다 — 항목 실패로 국한하는 방어.
            return failItem(item, ErrorCode.INVALID_IMAGE_TAG, e.getMessage());
        }

        ManifestInfo expected = manifestInfo != null ? manifestInfo : fetchManifestSafely(ref, item.getImageTag());
        if (expected == null) {
            return failItem(item, ErrorCode.IMAGE_NOT_FOUND, null);
        }

        // imagesDir 하위 tar파일 경로 생성 - 문자열 조립 대신 Path.resolve를 써서 경로 구분자를 OS에 맞게 처리한다.
        Path tarPath = imagesDir.resolve(ref.tarFileName());

        int attempt = 0;
        while (true) {
            // 아카이브 목적지는 기존 파일 수정을 지원하지 않는다 — 남은 tar가 있으면 재시도가
            // 매번 즉시 실패한다. 마지막 실패 시점이 아니라 매 시도 시작에 지워야 한다.
            deleteQuietly(tarPath);

            SkopeoResult result = runSkopeo(ref, tarPath, authFile);
            // exitCode=0이면 stderr가 비어있든 말든 성공으로 본다.
            if (result.exitCode() == 0) {
                return handleSuccess(item, expected, ref, tarPath);
            }

            // exitCode!=0이면 stderr를 마스킹해 로그와 항목 실패 사유에 남긴다.
            String maskedStderr =
                    CredentialMasker.mask(result.stderr(), ncrProperties.accessKey(), ncrProperties.secretKey(), authFile.base64Value());

            // 재시도 불가 stderr는 401/403/404, manifest unknown, not found, no space left 등이다.
            boolean retryable = !result.nonRetryable() && isRetryable(maskedStderr);

            // 타임아웃이면 재시도 가능하더라도 maxRetries를 넘어가면 포기한다.
            if (!retryable || attempt >= retryProperties.maxRetries()) {
                deleteQuietly(tarPath);
                return failItem(
                        item,
                        result.errorCode() != null
                                ? result.errorCode()
                                : (result.timedOut() ? ErrorCode.SKOPEO_TIMEOUT : ErrorCode.SKOPEO_FAILED),
                        "exit=%d, stderr=%s".formatted(result.exitCode(), maskedStderr));
            }
            attempt++;
            item.incrementRetryCount();
            packageItemRepository.save(item);
            // 재시도 전 백오프 대기 — 재시도 횟수에 따라 백오프를 늘린다. InterruptedException이면 호출자가 인터럽트된 경우다.
            RetryExecutor.sleepOrThrowOnInterrupt(retryProperties.backoffFor(attempt));
        }
    }

    /**
     * 확정 시점과 다운로드 직후를 REST로 두 번 조회해 대조한다 — 받는 도중 같은 태그가 다시 push된
     * 경우를 잡는 검사이지, 아카이브가 온전한지 보는 검사가 아니다. 후자는 skopeo가 copy 중 blob마다
     * digest를 검증하고 {@code --preserve-digests}가 보존 실패 시 0이 아닌 코드로 끝내 이미 담보된다
     * (아카이브를 다시 읽으면 파일 전체를 훑게 되므로 여기서 재검증하지 않는다).
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
                    current == null ? ErrorCode.DIGEST_UNVERIFIABLE : ErrorCode.DIGEST_MISMATCH,
                    "expected=%s, current=%s".formatted(expectedDigest, currentDigest));
        }

        long fileSize;
        try {
            fileSize = Files.size(tarPath);
        } catch (IOException e) {
            deleteQuietly(tarPath);
            return failItem(item, ErrorCode.ARCHIVE_UNREADABLE, e.getMessage());
        }
        if (fileSize == 0) {
            deleteQuietly(tarPath);
            return failItem(item, ErrorCode.ARCHIVE_EMPTY, null);
        }

        item.markDownloaded(fileSize);
        packageItemRepository.save(item);
        return true;
    }

    private boolean failItem(PackageItem item, ErrorCode errorCode, String detail) {
        return PackageItemFailure.fail(packageItemRepository, item, errorCode, detail);
    }

    /** 재시도 재개 시의 즉석 조회와 다운로드 직후 digest 재확인이 함께 쓴다. */
    private ManifestInfo fetchManifestSafely(ImageReference ref, String imageTagForLog) {
        try {
            return ncrRegistryClient.getManifest(ref).orElse(null);
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

        // 원본 형식을 유지해 아카이브 digest가 레지스트리 digest와 같아진다 — --format으로
        // 강제하면 인덱스에 붙은 buildx 어테스테이션(vnd.in-toto+json)에서 죽는다.
        command.add("--preserve-digests");

        // 빠지면 인덱스가 플랫폼 하나로 평탄화돼 digest가 어긋난다(무결성 대조가 항상 오탐).
        command.add("--multi-arch");
        command.add("all");
        if (isPlainHttp(ncrProperties.endpoint())) {
            command.add("--src-tls-verify=false");
        }
        command.add("docker://%s/%s:%s".formatted(host, ref.repository(), ref.tag()));

        // 목적지 참조가 index.json의 ref.name이 된다 — 완전 수식이 아니면 적재는 되는데 이름으로 못 쓴다.
        // 고객사로 나가는 파일이라 호스트는 NCR이 아닌 docker.io다(회귀: PackageJobDownloadFlowIntegrationTest).
        command.add("oci-archive:%s:docker.io/%s:%s".formatted(tarPath, canonicalRepository(ref), ref.tag()));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        // 기본값이 파이프라 닫지 않으면 프로세스가 끝나도 파일 디스크립터가 GC까지 남는다.
        pb.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            // StartupChecks가 조기 검출하지만 기동 이후 경로가 사라지는 경우를 방어한다.
            // 바이너리 누락은 재시도해도 소용없다 — 코드를 함께 실어 백오프를 건너뛰게 한다.
            return new SkopeoResult(-1, e.getMessage(), false, ErrorCode.SKOPEO_NOT_EXECUTABLE);
        }
        try {
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

            // 타임아웃은 false 반환이다 — InterruptedException은 호출자가 인터럽트된 경우다.
            boolean finished = process.waitFor(skopeoTimeoutSeconds, TimeUnit.SECONDS);
            // 타임아웃이면 프로세스를 강제 종료한다 — skopeo는 SIGTERM을 무시하고 SIGKILL로 죽는다.
            if (!finished) {
                process.destroyForcibly();
            }

            // stderrReader.join()는 타임아웃이 없으므로 skopeo 종료 후 5초만 기다린다.
            stderrReader.join(Duration.ofSeconds(5).toMillis());
            int exitCode = finished ? process.exitValue() : -1;

            return new SkopeoResult(exitCode, stderrBuffer.toString(), !finished);
        } catch (InterruptedException e) {
            // invokeAll은 호출자가 인터럽트되면 형제 태스크를 cancel(true)로 끊는다(실측) —
            // 여기서 안 죽이면 skopeo가 살아남아 지워질 tar에 계속 쓴다.
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("다운로드 대기 중 인터럽트되었습니다.", e);
        }
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null");
    }

    /**
     * skopeo 인증 파일을 만든다 — 임시 파일을 만들고 권한을 rw-------로 제한한다. Windows는 POSIX 권한을 지원하지 않으므로
     * UnsupportedOperationException이 나면 경고만 남기고 진행한다.
     */
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
            Files.writeString(authFile, objectMapper.writeValueAsString(content));
            return new AuthFile(authFile, authValue);
        } catch (IOException e) {
            throw new IllegalStateException("skopeo 인증 파일을 만들 수 없습니다.", e);
        }
    }

    /**
     * docker.io 기준 정규 저장소명. 네임스페이스가 없는 이름(NCR의 {@code cids}·{@code ocr}·
     * {@code piids}·{@code pips} 4개)은 Docker Hub 정규형이 {@code library/<이름>}이라, 그대로
     * 두면 기록된 이름과 조회 시 정규화된 이름이 또 어긋나 호스트를 안 붙였을 때와 똑같은
     * 증상이 난다(적재는 되는데 이름으로 못 쓰고 `docker images`에 두 행). 표시 이름에는
     * 영향이 없다 — Docker가 docker.io/library/를 표시에서 뗀다(실측).
     */
    private static String canonicalRepository(ImageReference ref) {
        return ref.repository().contains("/") ? ref.repository() : "library/" + ref.repository();
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

    /** {@code errorCode}가 있으면 stderr 분류보다 우선한다(예: 바이너리 자체가 없는 경우). */
    private record SkopeoResult(int exitCode, String stderr, boolean timedOut, ErrorCode errorCode) {

        SkopeoResult(int exitCode, String stderr, boolean timedOut) {
            this(exitCode, stderr, timedOut, null);
        }

        boolean nonRetryable() {
            return errorCode != null;
        }
    }

    /** authfile 경로와 그 안의 base64 자격 증명 — stderr 마스킹 대상으로도 쓴다. */
    private record AuthFile(Path path, String base64Value) {}
}
