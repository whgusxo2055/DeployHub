package com.deployhub.job.service;

import com.deployhub.common.BoundedParallelism;
import com.deployhub.common.ItemErrorCode;
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
import java.util.concurrent.Executor;
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
        // 포화 덧셈 — 항목이 각각 Long.MAX_VALUE로 포화하면 단순 합은 음수로 뒤집혀
        // 아래 비교가 무조건 통과한다(디스크 가드 fail-open). sumLayerSizes와 같은 패턴이다.
        long expectedTotal = 0L;
        for (ManifestInfo info : infos) {
            long size = info.totalSize();
            expectedTotal = expectedTotal + size < expectedTotal ? Long.MAX_VALUE : expectedTotal + size;
        }
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
            return failItem(item, ItemErrorCode.INVALID_IMAGE_TAG, e.getMessage());
        }

        ManifestInfo expected = manifestInfo != null ? manifestInfo : fetchManifestSafely(ref, item.getImageTag());
        if (expected == null) {
            return failItem(item, ItemErrorCode.IMAGE_NOT_FOUND, null);
        }

        Path tarPath = imagesDir.resolve(ref.tarFileName());

        int attempt = 0;
        while (true) {
            // 아카이브 목적지는 기존 파일 수정을 지원하지 않는다 — 남은 tar가 있으면 재시도가
            // 매번 즉시 실패한다. 마지막 실패 시점이 아니라 매 시도 시작에 지워야 한다.
            deleteQuietly(tarPath);
            SkopeoResult result = runSkopeo(ref, tarPath, authFile);
            if (result.exitCode() == 0) {
                return handleSuccess(item, expected, ref, tarPath);
            }

            String maskedStderr =
                    CredentialMasker.mask(result.stderr(), ncrProperties.accessKey(), ncrProperties.secretKey(), authFile.base64Value());
            boolean retryable = !result.nonRetryable() && isRetryable(maskedStderr);
            if (!retryable || attempt >= retryProperties.maxRetries()) {
                deleteQuietly(tarPath);
                return failItem(
                        item,
                        result.errorCode() != null
                                ? result.errorCode()
                                : (result.timedOut() ? ItemErrorCode.SKOPEO_TIMEOUT : ItemErrorCode.SKOPEO_FAILED),
                        "exit=%d, stderr=%s".formatted(result.exitCode(), maskedStderr));
            }
            attempt++;
            item.incrementRetryCount();
            packageItemRepository.save(item);
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
                    current == null ? ItemErrorCode.DIGEST_UNVERIFIABLE : ItemErrorCode.DIGEST_MISMATCH,
                    "expected=%s, current=%s".formatted(expectedDigest, currentDigest));
        }

        long fileSize;
        try {
            fileSize = Files.size(tarPath);
        } catch (IOException e) {
            deleteQuietly(tarPath);
            return failItem(item, ItemErrorCode.ARCHIVE_UNREADABLE, e.getMessage());
        }
        if (fileSize == 0) {
            deleteQuietly(tarPath);
            return failItem(item, ItemErrorCode.ARCHIVE_EMPTY, null);
        }

        item.markDownloaded(fileSize);
        packageItemRepository.save(item);
        return true;
    }

    private boolean failItem(PackageItem item, ItemErrorCode errorCode, String detail) {
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
        // 원본 바이트를 그대로 담아 아카이브 digest가 레지스트리 digest와 같아진다. 포맷을
        // 강제하면(--format v2s2 등) 안 된다 — 인덱스에 붙은 buildx 어테스테이션에서
        // "Unknown media type ... vnd.in-toto+json"으로 죽는다(NCR 12개 중 3개가 해당).
        command.add("--preserve-digests");
        // 인덱스를 평탄화하지 않고 통째로 담는다. 빠지면 skopeo가 플랫폼 하나만 골라
        // 담아 아카이브 digest가 인덱스 digest와 달라진다(무결성 대조가 항상 오탐).
        command.add("--multi-arch");
        command.add("all");
        if (isPlainHttp(ncrProperties.endpoint())) {
            command.add("--src-tls-verify=false");
        }
        command.add("docker://%s/%s:%s".formatted(host, ref.repository(), ref.tag()));
        // oci-archive:는 레이어 압축을 보존한다(docker-archive:는 전부 풀어 담아 산출물이 몇 배로 불어난다).
        //
        // 목적지 참조는 반드시 **레지스트리 호스트가 붙은 완전 수식 참조**여야 한다. 이 값이
        // index.json의 org.opencontainers.image.ref.name이 되는데, containerd 이미지 저장소는
        // 그 문자열을 이미지 이름으로 그대로 기록하기 때문이다. 호스트가 없으면("acme/x:1.0")
        // Docker는 조회할 때 docker.io/를 붙여 정규화하므로 기록된 이름과 영영 안 맞는다 —
        // 적재는 되는데 `docker run acme/x:1.0`이 Docker Hub에서 받으려 하고(pull access denied),
        // inspect/tag/rmi가 전부 "No such image"가 되며 `docker images`에 같은 행이 두 번 뜬다.
        //
        // 그 호스트로 실제 NCR 엔드포인트를 쓰면 안 된다 — 아카이브는 SharePoint를 거쳐 고객사로
        // 나가므로 사내 레지스트리 주소가 그대로 실린다. 대신 Docker의 기본 레지스트리인
        // docker.io를 명시한다. 조회 시 정규화 결과와 같아져 이름이 살아나고, `docker images`는
        // docker.io/ 접두사를 표시에서 떼므로 고객사가 보는 이름은 "acme/x:1.0" 그대로다(실측).
        command.add("oci-archive:%s:docker.io/%s:%s".formatted(tarPath, canonicalRepository(ref), ref.tag()));

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
            // 바이너리 누락은 재시도해도 소용없다 — 코드를 함께 실어 백오프를 건너뛰게 한다.
            return new SkopeoResult(-1, e.getMessage(), false, ItemErrorCode.SKOPEO_NOT_EXECUTABLE);
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
    private record SkopeoResult(int exitCode, String stderr, boolean timedOut, ItemErrorCode errorCode) {

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
