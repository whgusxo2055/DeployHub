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
 * FN-06-1 다운로드 + FN-07 재시도 (구현계획서 466-490행, DOWNLOADING 단계). {@code
 * manifestContext}가 비어 있으면(FN-07 수동 재시도 재개 경로 — VALIDATING을 다시 안 돈다)
 * 각 항목을 받기 직전에 매니페스트를 그 자리에서 다시 조회한다 — 전체 재검증(E-0501/E-0502
 * job 중단 판정 포함)은 하지 않고, digest 대조 기준값만 새로 얻는다.
 */
@Slf4j
@Service
public class PackageDownloadService {

    // oci-archive: 목적지는 압축(gzip) 레이어를 그대로 담으므로 산출 tar가 매니페스트
    // layers[].size 합계와 거의 같다(실측 7,416,956B → 7,434,240B, tar 헤더분 +0.2%).
    // 여유분은 tar 블록 정렬과 config/manifest 몫이다.
    private static final double REQUIRED_FREE_SPACE_RATIO = 1.2;
    /** 아카이브에서 읽은 digest로 blob 경로를 조립하기 전 형식을 확인한다(NCR·skopeo 모두 sha256만 쓴다). */
    private static final Pattern BLOB_DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");
    // "no space left"가 빠지면 디스크가 찬 상태에서 수 GB 재다운로드를 maxRetries만큼
    // 반복한다 — 매 시도 전 tarPath를 지우므로 공간이 잠깐 생겼다 다시 차기만 한다.
    // 사전 가드(checkDiskSpace)의 여유율을 1.2로 낮춘 뒤로는 실제로 여기 닿을 수 있다.
    private static final Pattern NON_RETRYABLE_STDERR = Pattern.compile(
            "(?i)unauthorized|forbidden|\\b401\\b|\\b403\\b|\\b404\\b|manifest unknown|not found|no space left");
    private static final int STDERR_CAPTURE_LIMIT = 8192;
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
        List<ManifestInfo> infos = targets.stream()
                .map(item -> manifestContext.get(item.getImageTag()))
                .filter(Objects::nonNull)
                .toList();
        // 크기 미상이 하나라도 섞이면 합계가 과소평가된다 — 0으로 더하면 "필요 용량 0"이 되어
        // 가드가 조용히 통과하므로, 아예 사전 확인을 건너뛰고 skopeo 실행 시점의 실제 실패에
        // 맡긴다(manifestContext가 빈 재시도 경로와 동일한 정책).
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
            // 아카이브 목적지는 기존 파일 수정을 지원하지 않는다("doesn't support modifying
            // existing images") — 이전 시도(또는 이전 크래시)가 남긴 부분/완성 tar가 있으면
            // 재시도가 매번 이 오류로 즉시 실패해 사실상 재시도가 안 된다. 매 시도 전에
            // 지운다(구현계획서 488행 "부분 파일 삭제 후 재다운로드").
            deleteQuietly(tarPath);
            SkopeoResult result = runSkopeo(ref, tarPath, authFile);
            if (result.exitCode() == 0) {
                // 후처리 실패는 재시도해도 같은 결과다(아카이브 내용이 아니라 조립 로직 문제) —
                // 다시 받지 않고 항목 실패로 끝낸다.
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
            sleep(retryProperties.backoffFor(attempt));
        }
    }

    /**
     * digest 대조는 아카이브에 담긴 값이 아니라 다운로드 직후 매니페스트를 다시 REST로
     * 조회해서 한다 — 태그가 인덱스를 가리키면(dev-ncr-sb 12개 중 3개) skopeo가 플랫폼
     * 하나로 평탄화해 담으므로 아카이브 안 digest는 그 플랫폼 매니페스트 것이고, FN-05가
     * REST로 관찰해 기록한 인덱스 digest와는 애초에 다른 값이다(항상 불일치로 오탐).
     * 대신 같은 REST 경로로 "확정 시점"과 "다운로드 직후" 두 시점의 원본 digest를 비교해
     * 재푸시 여부(TOCTOU)를 확인한다 — 스펙 의도(동일 태그 재푸시 탐지)는 그대로 지키면서
     * 목적지 포맷과 무관해진다.
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
        // oci-archive:는 레이어를 gzip 그대로 담는다 — docker-archive:는 레거시 포맷이 레이어를
        // diff_id(비압축 sha256)로 식별해서 전부 풀어 담느라 산출물이 1.76~3.37배로 불어난다.
        // 참조는 태그만이 아니라 "저장소:태그" 전체를 넘긴다 — 이 값이 index.json의
        // org.opencontainers.image.ref.name이 되고, Docker 29의 docker load가 태그를 그걸로
        // 복원한다(태그만 넘기면 "v1.0.0:latest" 같은 이름으로 적재된다 — 실측).
        command.add("oci-archive:%s:%s:%s".formatted(tarPath, ref.repository(), ref.tag()));

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

    /**
     * OCI 아카이브에 레거시 {@code manifest.json}을 덧붙인다 — Docker 28 이하의 {@code docker
     * load}는 이 파일이 없으면 적재 자체를 못 하고, 태그도 여기 {@code RepoTags}로만 복원한다
     * (29 이상은 {@code index.json}의 {@code org.opencontainers.image.ref.name}을 본다).
     * 두 곳을 다 채워야 전 버전에서 같은 이름으로 뜬다 — 19.03/20.10/23.0/25.0/27/29 실측.
     *
     * <p>풀었다 다시 묶지 않고 {@code tar -r}로 항목 하나만 덧붙인다. 10GB급 이미지에서
     * 압축 해제·재압축은 디스크 사용량을 두 배로 만드는데, 덧붙이기는 tar 끝의 종료 블록만
     * 다시 써서 증가분이 수 KB다(실측 7,431,168B → 7,434,240B).
     *
     * @return 실패 사유, 성공이면 {@code null}
     */
    private String appendLegacyManifest(ImageReference ref, Path tarPath) {
        Path workDirectory = null;
        try {
            // skopeo copy는 원본이 인덱스여도 플랫폼 하나로 평탄화하므로 manifests[0]은 항상
            // 이미지 매니페스트다(어테스테이션 8개를 포함한 16항목 인덱스로 실측 확인).
            JsonNode index = JSON_MAPPER.readTree(readArchiveEntry(tarPath, "index.json"));
            JsonNode manifest = JSON_MAPPER.readTree(
                    readArchiveEntry(tarPath, blobPath(index.path("manifests").path(0).path("digest").asText())));
            List<String> layers = new ArrayList<>();
            for (JsonNode layer : manifest.path("layers")) {
                // docker-archive:는 레이어를 전부 비압축으로 정규화해서 원본 압축 방식이
                // 드러나지 않았지만, oci-archive:는 그대로 보존한다 — zstd 레이어가 섞이면
                // Docker 29에서는 적재되고 28 이하에서만 "invalid tar header"로 실패한다.
                // 우리 CI/개발 환경은 최신 데몬이라 못 잡고 고객사에서만 터지므로 여기서 끊는다.
                // (dev-ncr-sb는 현재 전부 gzip이다 — 나중에 zstd로 push될 때를 막는 가드다.)
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
            // InterruptedException에서 파생된 예외는 여기 안 걸린다(runProcess가 별도 타입으로
            // 던진다) — 걸리면 셧다운 중 인터럽트가 "항목 영구 FAILED"로 굳어버린다.
            return e.getMessage() != null ? e.getMessage() : e.toString();
        } finally {
            if (workDirectory != null) {
                deleteQuietly(workDirectory.resolve("manifest.json"));
                deleteQuietly(workDirectory);
            }
        }
    }

    private byte[] readArchiveEntry(Path tarPath, String entryName) {
        // --occurrence=1은 항목을 찾으면 거기서 멈추게 하지만, skopeo가 쓰는 순서가
        // blobs → 매니페스트 → index.json → oci-layout이라 index.json과 매니페스트 blob은
        // 사실상 끝까지 훑는다. 즉 후처리는 아카이브 크기의 약 2배를 읽는다(10GB면 20GB).
        // 다운로드 자체가 훨씬 오래 걸려 문제되지 않지만, 성능을 볼 땐 이 비용을 감안할 것.
        ProcessResult result =
                runProcess(List.of("tar", "-xf", tarPath.toString(), "-O", "--occurrence=1", entryName), true);
        if (result.exitCode() != 0 || result.output().length == 0) {
            // exit=-1이면 tar 자체를 실행 못 한 경우다 — 그 사유가 output에 들어 있다.
            throw new ArchiveException(
                    "아카이브에서 %s를 읽지 못했습니다(exit=%d). %s"
                            .formatted(entryName, result.exitCode(), result.text()));
        }
        return result.output();
    }

    private static String blobPath(String digest) {
        if (!BLOB_DIGEST.matcher(digest).matches()) {
            // 우리가 방금 만든 아카이브에서 읽은 값이라 정상이면 여기 걸릴 일이 없다. 걸린다면
            // 형식이 바뀐 것이므로, 그 값을 tar 인자로 넘기지 않고 실패로 끊는다.
            throw new ArchiveException("아카이브의 digest 형식이 올바르지 않습니다.");
        }
        return "blobs/sha256/" + digest.substring("sha256:".length());
    }

    /**
     * tar처럼 출력이 작은 보조 프로세스 전용이다(skopeo는 stderr가 커질 수 있어 별도 리더
     * 스레드를 쓰는 {@link #runSkopeo}를 따로 둔다). 출력을 별도 스레드로 비우면서 대기해야
     * 타임아웃이 실제로 동작한다 — 같은 스레드에서 EOF까지 읽으면 EOF가 프로세스 종료 시점에나
     * 오므로 뒤따르는 {@code waitFor(timeout)}가 아무것도 못 끊는다.
     *
     * @param captureStdout true면 stdout만 받는다(stderr를 섞으면 JSON이 깨진다). false면
     *     stderr를 stdout에 합쳐 오류 메시지를 받는다.
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
            // 고아 프로세스를 남기지 않는다. ArchiveException이 아닌 타입으로 던져
            // appendLegacyManifest가 이걸 "항목 실패"로 흡수하지 않게 한다.
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
                // POSIX 권한을 지원하지 않는 파일시스템(Windows 로컬 실행 등) — 운영은
                // Linux 서버 전제라 권한 제한 없이 진행한다. 여기서 안 잡으면 로컬 개발
                // 실행 자체가 즉시 죽는다.
                log.warn("파일 권한 설정을 지원하지 않는 파일시스템입니다 — 권한 제한 없이 진행합니다: {}", authFile);
            }
            // 자격 증명에 따옴표·역슬래시가 섞여도 깨지지 않도록 문자열 조립 대신 Jackson으로 직렬화한다.
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

    private record ProcessResult(int exitCode, byte[] output) {
        String text() {
            return new String(output, StandardCharsets.UTF_8);
        }
    }

    /** 아카이브 후처리 실패 — 항목 실패로 흡수된다. 인터럽트와 구분하려고 별도 타입으로 둔다. */
    private static class ArchiveException extends RuntimeException {
        ArchiveException(String message) {
            super(message);
        }
    }

    /** authfile 경로 + 그 안에 든 base64(accessKey:secretKey) — stderr 마스킹 대상으로도 쓴다. */
    private record AuthFile(Path path, String base64Value) {}
}
