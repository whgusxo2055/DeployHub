package com.deployhub.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.deployhub.job.dto.PackageItemRetryRequest;
import com.deployhub.job.dto.PackageJobCreateRequest;
import com.deployhub.job.dto.PackageJobDetailResponse;
import com.deployhub.sharepoint.GraphFolderService;
import com.deployhub.sharepoint.GraphUploadService;
import com.deployhub.support.MySqlContainerSupport;
import com.deployhub.version.dto.MainVersionCreateRequest;
import com.deployhub.version.dto.MainVersionInfoResponse;
import com.deployhub.version.dto.SubVersionSavedResponse;
import com.deployhub.version.dto.SubVersionUpsertBatchRequest;
import com.deployhub.version.dto.SubVersionUpsertRequest;
import com.deployhub.version.dto.SubmitStatusChangeRequest;
import com.deployhub.version.entity.SubmitStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 4 완료 기준(구현계획서 502-507행) — FN-05/FN-06-1/FN-07을 실제 skopeo 바이너리 +
 * 로컬 Docker 레지스트리(무인증 {@code registry:2})로 검증한다. 가짜 skopeo 스크립트 대신
 * 진짜 바이너리를 쓰는 이유와 이 컨테이너가 {@link MySqlContainerSupport}의 싱글턴 규약을
 * 깨지 않는 이유는 Phase 4 계획 논의를 참고 — 이 클래스 하나만 이 컨테이너를 쓰고 다른
 * 테스트 클래스와 Spring 컨텍스트를 공유하지 않으므로 {@code @Testcontainers}를 평범하게
 * 써도 안전하다.
 *
 * <p>{@code NCR_TEST_IMAGE_TAG} 환경변수(실제 NCR에 이미 존재하는 image_tag)가 설정되어
 * 있으면 로컬 레지스트리 시딩을 건너뛰고, 이미 주입된 실 {@code NCR_ENDPOINT}/
 * {@code NCR_ACCESS_KEY}/{@code NCR_SECRET_KEY}(dev 프로필도 이제 이 env var들을 그대로
 * 흘려보낸다) 그대로 그 태그를 검증·다운로드한다 — NCR이 Pull 전용 권한(0.7절)이라 새로
 * push하지 않고 기존 태그만 읽는다. 이 모드는 실 자격증명이 없는 한 CI/로컬에서는 항상
 * 건너뛴다.
 */
@Testcontainers
class PackageJobDownloadFlowIntegrationTest extends MySqlContainerSupport {

    private static final boolean USE_REAL_NCR = System.getenv("NCR_TEST_IMAGE_TAG") != null;

    // 로컬 registry:2의 alpine은 11MB라 60초면 충분하지만, 실 NCR의 이미지는 수 GB다
    // (dev-ncr-sb 실측: 4.5GB짜리 OCI index가 172초). 실 NCR 모드에서만 넉넉히 잡는다 —
    // 로컬 모드 타임아웃을 같이 늘리면 진짜 멈춤을 60초가 아니라 15분 뒤에 알게 된다.
    private static final Duration DONE_TIMEOUT = Duration.ofSeconds(USE_REAL_NCR ? 900 : 60);
    private static final int PROCESS_TIMEOUT_SECONDS = USE_REAL_NCR ? 900 : 60;
    private static final String TEST_REPOSITORY = "deployhub-test/alpine";
    private static final String TEST_TAG = "3.19";

    private static String testImageTag;

    @Container
    static final GenericContainer<?> REGISTRY =
            new GenericContainer<>("registry:2").withExposedPorts(5000).waitingFor(Wait.forHttp("/v2/").forStatusCode(200));

    @DynamicPropertySource
    static void registryProperties(DynamicPropertyRegistry registry) {
        if (USE_REAL_NCR) {
            return; // 실 NCR_ENDPOINT/NCR_ACCESS_KEY/NCR_SECRET_KEY는 dev 프로필 env var 패턴으로 이미 흘러들어온다.
        }
        registry.add(
                "deployhub.registry.endpoint",
                () -> "http://" + REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(5000));
        registry.add("deployhub.registry.access-key", () -> "dummy");
        registry.add("deployhub.registry.secret-key", () -> "dummy");
    }

    @BeforeAll
    static void seedRegistry() throws IOException, InterruptedException {
        // skopeo/docker는 Windows에 공식 빌드가 없다(Linux 전용) — IntelliJ/Windows JDK로
        // 이 클래스를 돌리면 예전엔 IOException으로 하드 실패했다. 대신 건너뛴다: WSL
        // 터미널에서 돌리라는 안내를 CLAUDE.md에 남겼지만, 그걸 모르고 Windows에서 그냥
        // 실행해도 다른 13개 테스트 클래스처럼 "실패"가 아니라 "skipped"로 보이게 한다.
        Assumptions.assumeTrue(isCommandAvailable("skopeo"), "skopeo가 없어 이 테스트를 건너뜁니다 (WSL에서 실행하세요).");
        Assumptions.assumeTrue(isCommandAvailable("docker"), "docker가 없어 이 테스트를 건너뜁니다.");

        if (USE_REAL_NCR) {
            testImageTag = System.getenv("NCR_TEST_IMAGE_TAG");
            return;
        }
        testImageTag = TEST_REPOSITORY + ":" + TEST_TAG;
        String registryHost = REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(5000);
        runProcess(
                "skopeo",
                "copy",
                "--dest-tls-verify=false",
                "docker://alpine:" + TEST_TAG,
                "docker://" + registryHost + "/" + testImageTag);
    }

    private static boolean isCommandAvailable(String command) {
        try {
            Process process =
                    new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${deployhub.work-dir}")
    private String workDir;

    // 이 클래스는 FN-05/FN-06-1/FN-07(다운로드 파이프라인)만 검증 대상이다 — Phase 5
    // (FN-08/09, SharePoint 업로드)는 실 Graph 자격증명이 준비되지 않아 대상 밖이므로
    // 무력화한다. 그러지 않으면 dev 프로필의 placeholder Graph 자격증명으로 실제 네트워크
    // 호출을 시도하다 실패해, 이 테스트가 검증하려는 것과 무관한 이유로 Job이 FAILED가
    // 된다(코드리뷰로 발견 — 이전엔 이 때문에 DONE 단언을 완화해 테스트 검증력이 약해졌다).
    @MockitoBean
    private GraphFolderService graphFolderService;

    @MockitoBean
    private GraphUploadService graphUploadService;

    @AfterEach
    void 데이터_정리() throws IOException {
        jdbcTemplate.execute("DELETE FROM package_item");
        jdbcTemplate.execute("DELETE FROM package_job");
        jdbcTemplate.execute("DELETE FROM sub_version");
        jdbcTemplate.execute("DELETE FROM main_version");
        // 버전명이 테스트마다 고정 문자열이라, 이전 실행이 남긴 .tar가 있으면 다음 실행의
        // skopeo copy가 "doesn't support modifying existing images"로
        // 즉시 실패한다 — WORK_DIR 자체 재사용은 프로덕션 재시도 경로에서도 고쳤지만
        // (PackageDownloadService), 테스트 실행 간 격리를 위해 이 dev 프로필 전용
        // 작업 디렉터리(./build/tmp/deployhub-jobs) 전체를 지운다.
        Path root = Path.of(workDir);
        if (Files.exists(root)) {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> deleteQuietly(path));
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 테스트 정리 실패는 다음 테스트 실행에 영향 없다 — 무시한다.
        }
    }

    @Test
    void 정상_완료되고_tar가_docker_load로_원래_태그를_복원한다() throws IOException, InterruptedException {
        String versionName = "2026.20.01";
        registerMainVersion(versionName);
        registerAndSubmitSubVersion(versionName, "test", "1.0.0", List.of(testImageTag));

        ResponseEntity<PackageJobDetailResponse> created = createPackageJob(versionName, List.of(testImageTag));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        await().atMost(DONE_TIMEOUT).untilAsserted(() -> assertThat(getJob(versionName)
                        .getBody()
                        .job()
                        .status())
                .isEqualTo("DONE"));

        PackageJobDetailResponse finalState = getJob(versionName).getBody();
        assertThat(finalState.items()).hasSize(1);
        assertThat(finalState.items().get(0).status()).isEqualTo("DOWNLOADED");
        assertThat(finalState.items().get(0).fileSize()).isGreaterThan(0);

        // 파일명은 ImageReference.tarFileName()이 해시 접미사까지 붙여 만든다(충돌 방지,
        // 코드리뷰로 발견된 버그의 수정) — 여기서 그 로직을 그대로 재계산하는 대신,
        // images/ 아래 실제로 만들어진 .tar 하나를 그대로 찾아서 쓴다.
        Path imagesDir = Path.of(workDir, versionName, "images");
        List<Path> tarFiles;
        try (var stream = Files.list(imagesDir)) {
            tarFiles = stream.filter(p -> p.toString().endsWith(".tar")).toList();
        }
        assertThat(tarFiles).hasSize(1);
        Path tarPath = tarFiles.get(0);

        // 산출물이 OCI 레이아웃 + 레거시 manifest.json 하이브리드여야 한다. oci-layout이
        // 사라지면 docker-archive:로 되돌아간 것이고(레이어가 풀려 2~3배로 불어난다),
        // manifest.json이 없으면 Docker 28 이하가 적재조차 못 한다.
        String entries = runProcess("tar", "-tf", tarPath.toString());
        assertThat(entries).contains("oci-layout").contains("manifest.json");
        assertThat(entries).doesNotContain("layer.tar");

        // 아래 docker load는 호스트의 최신 데몬에서 도는데, Docker 29는 index.json만 보고
        // manifest.json을 통째로 무시한다 — Config/Layers가 존재하지 않는 blob을 가리켜도
        // 통과한다(실측). 즉 이 검증이 없으면 조립 로직 전체가 무테스트로 남고, 깨져도
        // Docker 28 이하를 쓰는 고객사에서만 드러난다.
        JsonNode legacyManifest = new ObjectMapper()
                .readTree(runProcess("tar", "-xf", tarPath.toString(), "-O", "manifest.json"))
                .get(0);
        assertThat(legacyManifest.get("RepoTags").get(0).asText()).isEqualTo(testImageTag);
        assertThat(entries).contains(legacyManifest.get("Config").asText());
        assertThat(legacyManifest.get("Layers")).isNotEmpty();
        for (JsonNode layer : legacyManifest.get("Layers")) {
            assertThat(entries).contains(layer.asText());
        }

        String loadOutput = runProcess("docker", "load", "-i", tarPath.toString());
        assertThat(loadOutput).contains(testImageTag);
    }

    @Test
    void 존재하지_않는_태그는_검증_단계에서_FAILED로_처리된다() {
        String versionName = "2026.20.02";
        String missingTag = TEST_REPOSITORY + ":does-not-exist";
        registerMainVersion(versionName);
        registerAndSubmitSubVersion(versionName, "test", "1.0.0", List.of(missingTag));

        ResponseEntity<PackageJobDetailResponse> created = createPackageJob(versionName, List.of(missingTag));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            PackageJobDetailResponse polled = getJob(versionName).getBody();
            assertThat(polled.job().status()).isEqualTo("FAILED");
            assertThat(polled.items().get(0).status()).isEqualTo("FAILED");
            assertThat(polled.items().get(0).errorMessage()).contains("E-0501");
        });
    }

    @Test
    void 수동_재시도로_FAILED_항목만_복구된다() throws IOException {
        String versionName = "2026.20.03";
        registerMainVersion(versionName);
        registerAndSubmitSubVersion(versionName, "test", "1.0.0", List.of(testImageTag));
        // 오케스트레이터 실행 타이밍에 기대지 않고 FAILED 상태를 직접 만든다(기존 Phase3
        // 테스트 관례와 동일) — retry() 로직 자체(대상 선정·상태 전이)만 검증 대상으로 좁힌다.
        // 작업 디렉터리는 실제로 한 번 다운로드를 시도했었다는 전제라 미리 만들어둔다 —
        // 없으면 E-0703(작업 디렉터리 소실)로 막혀 이 테스트의 의도(대상 선정)를 못 본다.
        Files.createDirectories(Path.of(workDir, versionName, "images"));
        jdbcTemplate.update(
                "INSERT INTO package_job (version_name, status, created_by) VALUES (?, 'FAILED', 'tester')", versionName);
        jdbcTemplate.update(
                "INSERT INTO package_item (version_name, image_tag, status, error_message) VALUES (?, ?, 'FAILED', 'E-0601: 이전 실패')",
                versionName,
                testImageTag);

        ResponseEntity<PackageJobDetailResponse> retried = restTemplate.postForEntity(
                "/api/package-jobs/{versionName}/retry",
                new PackageItemRetryRequest(null, false),
                PackageJobDetailResponse.class,
                versionName);

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody().job().status()).isEqualTo("DOWNLOADING");
        assertThat(retried.getBody().items().get(0).status()).isEqualTo("PENDING");

        await().atMost(DONE_TIMEOUT).untilAsserted(() -> assertThat(getJob(versionName)
                        .getBody()
                        .job()
                        .status())
                .isEqualTo("DONE"));
    }

    @Test
    void DONE_Job은_재시도가_거부된다() {
        String versionName = "2026.20.04";
        registerMainVersion(versionName);
        jdbcTemplate.update(
                "INSERT INTO package_job (version_name, status, created_by) VALUES (?, 'DONE', 'tester')", versionName);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/package-jobs/{versionName}/retry",
                new PackageItemRetryRequest(null, false),
                String.class,
                versionName);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("E-0702");
    }

    @Test
    void 작업_디렉터리가_없으면_force_없이는_재시도가_거부된다() {
        String versionName = "2026.20.05";
        registerMainVersion(versionName);
        jdbcTemplate.update(
                "INSERT INTO package_job (version_name, status, created_by) VALUES (?, 'FAILED', 'tester')", versionName);
        jdbcTemplate.update(
                "INSERT INTO package_item (version_name, image_tag, status) VALUES (?, ?, 'FAILED')",
                versionName,
                testImageTag);
        // work-dir 아래 이 버전 디렉터리를 아예 만들지 않는다 — 소실 상태를 그대로 재현한다.

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/package-jobs/{versionName}/retry",
                new PackageItemRetryRequest(null, false),
                String.class,
                versionName);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("E-0703");
    }

    private void registerMainVersion(String versionName) {
        ResponseEntity<MainVersionInfoResponse> response = restTemplate.postForEntity(
                "/api/main-versions", new MainVersionCreateRequest(versionName, null, null), MainVersionInfoResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void registerAndSubmitSubVersion(String versionName, String code, String version, List<String> imageTags) {
        ResponseEntity<SubVersionSavedResponse[]> saved = restTemplate.exchange(
                "/api/main-versions/{versionName}/sub-versions",
                HttpMethod.PUT,
                new HttpEntity<>(new SubVersionUpsertBatchRequest(
                        List.of(new SubVersionUpsertRequest(code, version, null, 1, imageTags)))),
                SubVersionSavedResponse[].class,
                versionName);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long id = saved.getBody()[0].id();
        restTemplate.exchange(
                "/api/sub-versions/{id}/submit-status",
                HttpMethod.PATCH,
                new HttpEntity<>(new SubmitStatusChangeRequest(SubmitStatus.UPDATED, "tester")),
                Void.class,
                id);
    }

    private ResponseEntity<PackageJobDetailResponse> createPackageJob(String versionName, List<String> imageTags) {
        return restTemplate.postForEntity(
                "/api/main-versions/{versionName}/package-job",
                new PackageJobCreateRequest(imageTags, "tester", false),
                PackageJobDetailResponse.class,
                versionName);
    }

    private ResponseEntity<PackageJobDetailResponse> getJob(String versionName) {
        return restTemplate.getForEntity("/api/package-jobs/{versionName}", PackageJobDetailResponse.class, versionName);
    }

    private static String runProcess(String... command) throws IOException, InterruptedException {
        Process process =
                new ProcessBuilder(command).redirectErrorStream(true).start();
        // 출력을 별도 스레드로 비우면서 waitFor 한다 — 같은 스레드에서 readAllBytes()를
        // 먼저 부르면 EOF가 프로세스 종료 후에야 오므로 거기서 무한정 막히고, 뒤의
        // waitFor는 이미 끝난 프로세스를 확인만 해 타임아웃이 무력해진다(CLAUDE.md).
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "(출력을 읽지 못했습니다: %s)".formatted(e);
            }
        });
        boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException(
                    "명령이 %d초 안에 끝나지 않았습니다(%s)".formatted(PROCESS_TIMEOUT_SECONDS, List.of(command)));
        }
        String captured = output.join();
        if (process.exitValue() != 0) {
            throw new IllegalStateException("명령 실행 실패(%s): %s".formatted(new ArrayList<>(List.of(command)), captured));
        }
        return captured;
    }
}
