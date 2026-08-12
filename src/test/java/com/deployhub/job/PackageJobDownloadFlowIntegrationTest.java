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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    // 네임스페이스 없는 이름이다 — NCR의 인덱스 3개(cids·ocr·pips)가 모두 이 형태이고,
    // docker.io 정규형이 library/<이름>이라 이 테스트가 그 경로까지 함께 검증한다.
    private static final String MULTI_ARCH_REPOSITORY = "alpinemulti";
    private static final String TEST_TAG = "3.19";

    private static String testImageTag;
    private static String multiArchImageTag;
    /** 로컬 registry:2 호스트:포트 — digest 대조 시 레지스트리를 다시 조회하는 데 쓴다. */
    private static String registryHost;

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
        registryHost = REGISTRY.getHost() + ":" + REGISTRY.getMappedPort(5000);
        // --format v2s2로 밀어 넣는 게 중요하다. alpine 원본은 OCI 형식이라 그대로 시딩하면
        // oci-archive: 목적지에서 변환이 일어나지 않아, --preserve-digests가 빠져도 digest가
        // 우연히 일치한다(= 아래 digest 단언이 아무것도 못 잡는 가짜 안전망이 된다).
        // dev-ncr-sb 실물도 12개 중 9개가 docker schema2라 이쪽이 실제 구성에 가깝다.
        runProcess(
                "skopeo",
                "copy",
                "--format",
                "v2s2",
                "--dest-tls-verify=false",
                "docker://alpine:" + TEST_TAG,
                "docker://" + registryHost + "/" + testImageTag);

        // 인덱스 경로 검증용 — 원본 alpine은 멀티아치 OCI 인덱스다. --all로 인덱스째 밀어 넣어야
        // skopeo의 --multi-arch all이 실제로 하는 일(평탄화 안 함)을 테스트가 잡을 수 있다.
        multiArchImageTag = MULTI_ARCH_REPOSITORY + ":" + TEST_TAG;
        runProcess(
                "skopeo",
                "copy",
                "--all",
                "--dest-tls-verify=false",
                "docker://alpine:" + TEST_TAG,
                "docker://" + registryHost + "/" + multiArchImageTag);
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

        // 산출물은 순수 OCI 레이아웃이다. oci-layout이 사라지면 docker-archive:로 되돌아간
        // 것이고(레이어가 풀려 2~3배로 불어난다), layer.tar가 보이면 압축이 풀린 것이다.
        // 레거시 manifest.json은 더는 넣지 않는다 — containerd 이미지 저장소가 index.json을
        // 직접 읽는다(구버전 Docker 미지원 결정에 따라 하이브리드 조립을 제거했다).
        String entries = runProcess("tar", "-tf", tarPath.toString());
        assertThat(entries).contains("oci-layout").contains("index.json");
        assertThat(entries).doesNotContain("layer.tar");

        // 아카이브 digest가 레지스트리 digest와 같아야 한다 — skopeo에서 --preserve-digests가
        // 빠지면 매니페스트를 OCI로 변환해 담아 digest가 반드시 달라지므로, 이 단언이 그 회귀를
        // 잡는다. (실 NCR 모드는 skopeo inspect에 자격증명이 따로 필요해 로컬 모드에서만 확인한다.)
        if (!USE_REAL_NCR) {
            String registryDigest = registryDigestOf(testImageTag);
            String archiveDigest = new ObjectMapper()
                    .readTree(runProcess("tar", "-xf", tarPath.toString(), "-O", "index.json"))
                    .get("manifests")
                    .get(0)
                    .get("digest")
                    .asText();
            assertThat(archiveDigest).isEqualTo(registryDigest);
        }

        // index.json의 ref.name은 완전 수식 참조(docker.io/…)여야 한다. 호스트가 빠지면
        // containerd 저장소가 정규화 불가능한 이름으로 기록해, 적재는 되는데 그 이름으로
        // run/inspect/tag/rmi가 전부 실패하고 `docker images`에 같은 행이 두 번 뜬다.
        // NCR 엔드포인트를 쓰지 않는 이유는 아카이브가 고객사로 나가기 때문이다(주소 노출).
        String refName = new ObjectMapper()
                .readTree(runProcess("tar", "-xf", tarPath.toString(), "-O", "--occurrence=1", "index.json"))
                .get("manifests")
                .get(0)
                .get("annotations")
                .get("org.opencontainers.image.ref.name")
                .asText();
        assertThat(refName).isEqualTo("docker.io/" + testImageTag);

        // 순수 OCI 레이아웃이라 이 docker load는 **containerd 이미지 저장소**를 요구한다 —
        // classic(graph driver) 데몬에서는 "does not contain a manifest.json"으로 깨진다.
        // 서버/CI는 Docker 29(기본 containerd)라 통과하지만, classic로 설정한 로컬에서
        // 이 테스트만 깨지면 그건 코드가 아니라 데몬 설정 문제다.
        String loadOutput = runProcess("docker", "load", "-i", tarPath.toString());
        assertThat(loadOutput).contains(testImageTag);

        // 적재된 이미지를 **이름으로** 실제로 쓸 수 있어야 한다 — "Loaded image:" 출력만으로는
        // 이름이 쓸 수 있는지 알 수 없다(호스트 없는 이름도 적재 자체는 성공한다).
        // 고객사가 치는 이름은 docker.io/ 없는 형태이므로 그대로 조회되어야 한다.
        assertThat(runProcess("docker", "image", "inspect", testImageTag)).contains(testImageTag);
        // 같은 이미지가 두 행으로 뜨면 이름 기록이 잘못된 것이다.
        assertThat(runProcess("docker", "images", "--format", "{{.Repository}}:{{.Tag}}")
                        .lines()
                        .filter(testImageTag::equals)
                        .count())
                .isEqualTo(1);
        runProcess("docker", "rmi", testImageTag);
    }

    @Test
    void 인덱스_이미지는_평탄화되지_않고_인덱스_digest_그대로_담긴다() throws Exception {
        Assumptions.assumeFalse(USE_REAL_NCR, "로컬 registry:2에 시딩한 멀티아치 인덱스 전용 검증입니다.");
        String versionName = "2026.20.05";
        registerMainVersion(versionName);
        registerAndSubmitSubVersion(versionName, "test", "1.0.0", List.of(multiArchImageTag));

        ResponseEntity<PackageJobDetailResponse> created = createPackageJob(versionName, List.of(multiArchImageTag));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        await().atMost(DONE_TIMEOUT).untilAsserted(() -> assertThat(getJob(versionName)
                        .getBody()
                        .job()
                        .status())
                .isEqualTo("DONE"));

        Path tarPath;
        try (var stream = Files.list(Path.of(workDir, versionName, "images"))) {
            tarPath = stream.filter(p -> p.toString().endsWith(".tar")).findFirst().orElseThrow();
        }

        // skopeo에서 --multi-arch all이 빠지면 인덱스가 플랫폼 하나로 평탄화돼 담기고,
        // index.json에는 그 플랫폼 매니페스트 digest가 들어간다 — 레지스트리가 태그에 대해
        // 보고하는 인덱스 digest와 절대 같아질 수 없다. 이 단언이 그 회귀를 잡는다.
        JsonNode entry = new ObjectMapper()
                .readTree(runProcess("tar", "-xf", tarPath.toString(), "-O", "--occurrence=1", "index.json"))
                .get("manifests")
                .get(0);
        assertThat(entry.get("digest").asText()).isEqualTo(registryDigestOf(multiArchImageTag));
        assertThat(entry.get("mediaType").asText()).contains("index");

        // 네임스페이스 없는 이름은 docker.io 정규형이 library/<이름>이다 — 안 붙이면 기록된
        // 이름과 조회 시 정규화된 이름이 어긋나, 적재는 되는데 이름으로 못 쓰고 행이 둘로 뜬다.
        assertThat(entry.get("annotations").get("org.opencontainers.image.ref.name").asText())
                .isEqualTo("docker.io/library/" + multiArchImageTag);

        runProcess("docker", "load", "-i", tarPath.toString());
        assertThat(runProcess("docker", "image", "inspect", multiArchImageTag)).contains(multiArchImageTag);
        assertThat(runProcess("docker", "images", "--format", "{{.Repository}}:{{.Tag}}")
                        .lines()
                        .filter(multiArchImageTag::equals)
                        .count())
                .isEqualTo(1);
        runProcess("docker", "rmi", multiArchImageTag);
    }

    /**
     * 레지스트리가 태그에 대해 보고하는 매니페스트 digest({@code Docker-Content-Digest} 헤더).
     * {@code skopeo inspect}를 쓰지 않는 이유는 두 가지다 — {@code --format {{.Digest}}}는 인덱스에서
     * 플랫폼 하나를 골라 그 digest를 주고, {@code --raw}는 이 클래스의 {@code runProcess}가 stderr를
     * 합쳐 읽어 본문이 오염될 수 있다.
     */
    private static String registryDigestOf(String imageTag) throws IOException, InterruptedException {
        int separator = imageTag.lastIndexOf(':');
        String url = "http://%s/v2/%s/manifests/%s"
                .formatted(registryHost, imageTag.substring(0, separator), imageTag.substring(separator + 1));
        HttpResponse<Void> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(url))
                                .header(
                                        "Accept",
                                        String.join(
                                                ",",
                                                "application/vnd.docker.distribution.manifest.v2+json",
                                                "application/vnd.oci.image.manifest.v1+json",
                                                "application/vnd.docker.distribution.manifest.list.v2+json",
                                                "application/vnd.oci.image.index.v1+json"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
        return response.headers().firstValue("Docker-Content-Digest").orElseThrow();
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
                new HttpEntity<>(new SubmitStatusChangeRequest(SubmitStatus.UPDATED)),
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
