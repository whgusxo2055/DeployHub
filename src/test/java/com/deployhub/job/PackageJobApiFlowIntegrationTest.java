package com.deployhub.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verifyNoInteractions;

import com.deployhub.job.dto.PackageItemResponse;
import com.deployhub.job.dto.PackageJobCreateRequest;
import com.deployhub.job.dto.PackageJobDetailResponse;
import com.deployhub.job.service.OrphanJobCleaner;
import com.deployhub.registry.ImageTagChecker;
import com.deployhub.support.MySqlContainerSupport;
import com.deployhub.version.dto.MainVersionCreateRequest;
import com.deployhub.version.dto.MainVersionInfoResponse;
import com.deployhub.version.dto.SubVersionSavedResponse;
import com.deployhub.version.dto.SubVersionUpsertRequest;
import com.deployhub.version.entity.SubmitStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Phase 3 완료 기준(구현계획서 431-440행) — 매니페스트 확정, FN-11 중복 방지, 고아 Job
 * 정리를 실제 MySQL 컨테이너 위에서 HTTP 엔드투엔드로 검증한다. 이 클래스는 FN-03/FN-11
 * (매니페스트 확정·중복 방지) 로직만 다룬다 — Job이 실제로 DONE까지 도달하는 것은 Phase 4가
 * {@link PackageJobDownloadFlowIntegrationTest}에서 실 레지스트리로 검증한다. 여기서는
 * {@code dev} 프로필의 placeholder NCR 엔드포인트를 그대로 쓰므로, 오케스트레이터가 실제로
 * 실행되면 VALIDATING에서 반드시 FAILED로 끝난다 — 그 사실 자체(오케스트레이터가 정말
 * 시작됐는지)만 확인하고, 재사용(DONE/FAILED/진행중) 시나리오는 그 상태를 jdbcTemplate으로
 * 직접 만들어 오케스트레이터의 실제 완료 여부와 무관하게 검증 대상을 좁힌다.
 */
class PackageJobApiFlowIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrphanJobCleaner orphanJobCleaner;

    // 실동작을 그대로 두는 spy다 — 다른 시나리오의 검증 경로를 바꾸지 않으면서 "불렸는지"만 본다.
    @MockitoSpyBean
    private ImageTagChecker imageTagChecker;

    // placeholder.invalid는 DNS조차 해석되지 않아 NCR 호출이 매번 재시도 정책을 다 태운다
    // (기본 backoff 5s+15s+45s) — 이 클래스는 그 실패 자체를 기다리므로 재시도를 꺼서
    // Awaitility 타임아웃 안에 끝나게 한다. 다른 시나리오(FN-03/FN-11 동기 검증)는 이
    // 값과 무관하다.
    @DynamicPropertySource
    static void fastRetry(DynamicPropertyRegistry registry) {
        registry.add("deployhub.retry.max-retries", () -> 0);
    }

    @AfterEach
    void 데이터_정리() {
        // 비동기 Job이 아직 돌고 있으면 PackageValidationService의 saveAll이 방금 지운 항목을
        // detached merge로 되살려 넣어(INSERT) 뒤이은 package_job 삭제가 FK로 죽는다 —
        // 전체 스위트 부하에서만 나던 간헐 실패다. 상태를 기다릴 수는 없다(진행 중 Job을 직접
        // 넣어 두는 테스트가 있다) — 조용해질 때까지 삭제를 다시 시도한다.
        await().atMost(Duration.ofSeconds(10)).ignoreExceptions().untilAsserted(() -> {
            jdbcTemplate.execute("DELETE FROM package_item");
            jdbcTemplate.execute("DELETE FROM package_job");
        });
        jdbcTemplate.execute("DELETE FROM sub_version");
        jdbcTemplate.execute("DELETE FROM main_version");
    }

    /**
     * 싼 검사가 레지스트리 조회보다 앞서야 한다 — 태그 하나당 Basic 401 → 토큰 → 재호출로 3왕복이고
     * imageTags 상한이 500이라, 순서가 뒤집히면 오타 하나가 NCR에 1,500회를 태우고 나서야 404가 된다.
     */
    @Test
    void 없는_메인버전_생성_요청은_레지스트리를_부르지_않는다() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/main-versions/{versionName}/package-job",
                new PackageJobCreateRequest(List.of("api:2.0.0")),
                String.class,
                "2099.12.31.001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("E-0101");
        verifyNoInteractions(imageTagChecker);
    }

    @Test
    void 지정한_태그만_확정되고_오케스트레이터가_실행된다() {
        registerMainVersion("2026.10.01.001");
        registerAndSubmitSubVersion("2026.10.01.001", "pips", "1.0.0", null);

        registerMainVersion("2026.10.02.001");
        registerAndSubmitSubVersion("2026.10.02.001", "pips", "1.0.0", null);
        registerAndSubmitSubVersion("2026.10.02.001", "api", "2.0.0", null);

        // 대상은 요청이 준 목록뿐이다 — 지정하지 않은 pips:1.0.0이 딸려 들어가면 안 된다.
        ResponseEntity<PackageJobDetailResponse> created =
                createPackageJob("2026.10.02.001", List.of("api:2.0.0"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().items()).extracting(PackageItemResponse::imageTag).containsExactly("api:2.0.0");

        // placeholder NCR 엔드포인트라 VALIDATING을 실제로 시도하다 FAILED로 끝난다 —
        // "DONE까지 도달"은 실 레지스트리를 쓰는 PackageJobDownloadFlowIntegrationTest가 검증한다.
        // 여기서는 오케스트레이터가 정말 PENDING을 벗어나 실행됐다는 것만 확인한다.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    ResponseEntity<PackageJobDetailResponse> polled = restTemplate.getForEntity(
                            "/api/package-jobs/{versionName}", PackageJobDetailResponse.class, "2026.10.02.001");
                    assertThat(polled.getBody().job().status()).isEqualTo("FAILED");
                    assertThat(polled.getBody().items()).extracting(PackageItemResponse::imageTag)
                            .containsExactly("api:2.0.0");
                });
    }

    @Test
    void 미변경_컴포넌트만_명시해도_부분_패키징된다() {
        registerMainVersion("2026.10.41.001");
        registerAndSubmitSubVersion("2026.10.41.001", "pips", "1.0.0", null);

        registerMainVersion("2026.10.42.001");
        registerAndSubmitSubVersion("2026.10.42.001", "pips", "1.0.0", null); // 직전과 동일 → 미변경
        registerAndSubmitSubVersion("2026.10.42.001", "api", "2.0.0", null); // 신규 → 변경

        // 선택 범위는 메인버전의 전체 컴포넌트다 — 직전 버전과 동일한 미변경분만 골라도 통과해야 한다.
        ResponseEntity<PackageJobDetailResponse> created =
                createPackageJob("2026.10.42.001", List.of("pips:1.0.0"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().items())
                .extracting(PackageItemResponse::imageTag)
                .containsExactly("pips:1.0.0");
    }

    @Test
    void 태그를_지정하지_않으면_E_0301로_거부된다() {
        registerMainVersion("2026.10.11.001");
        registerAndSubmitSubVersion("2026.10.11.001", "pips", "1.0.0", null);

        // imageTags는 필수다 — 변경분으로 대신 채워 주지 않는다.
        assertThat(createPackageJobRaw("2026.10.11.001", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<String> empty = createPackageJobRaw("2026.10.11.001", List.of());
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(empty.getBody()).contains("E-0301");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM package_job WHERE version_name = ?", Integer.class, "2026.10.11.001"))
                .isZero();
    }

    @Test
    void PENDING_서브버전이_남아있으면_E_0305로_거부된다() {
        registerMainVersion("2026.10.21.001");
        // registerAndSubmitSubVersion을 쓰지 않고 PENDING으로 등록해 확인 대기 상태를 만든다.
        putSubVersion("2026.10.21.001", new SubVersionUpsertRequest("pips", "1.0.0", null, 1, SubmitStatus.PENDING, null));

        ResponseEntity<String> response = createPackageJobRaw("2026.10.21.001", List.of("pips:1.0.0"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("E-0305");
    }

    @Test
    void 없는_태그나_중복_태그를_지정하면_E_0301로_거부된다() {
        registerMainVersion("2026.10.31.001");
        registerAndSubmitSubVersion("2026.10.31.001", "pips", "1.0.0", null);

        ResponseEntity<String> unknownTag =
                createPackageJobRaw("2026.10.31.001", List.of("not-exist:1.0"));
        assertThat(unknownTag.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknownTag.getBody()).contains("E-0301");

        ResponseEntity<String> duplicateTag =
                createPackageJobRaw("2026.10.31.001", List.of("pips:1.0.0", "pips:1.0.0"));
        assertThat(duplicateTag.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // 파일명 충돌과 사유가 갈려야 한다 — 고칠 방법이 "하나 지워라"와 "이미지 이름을 바꿔라"로 다르다.
        assertThat(duplicateTag.getBody()).contains("E-0301").contains("duplicated");
    }

    @Test
    void 파일명이_겹치는_태그_조합은_E_0301로_거부된다() {
        // tar 파일명은 '/'·':'를 '_'로 치환해 만든다 — 치환이 단사가 아니라 "a/b:1"과 "a_b:1"이
        // 같은 이름이 된다. 두 항목은 같은 폴더에 병렬로 내려받으므로 여기서 막지 않으면
        // 한쪽이 다른 쪽을 덮어쓴 채 고객사로 나간다.
        registerMainVersion("2026.10.33.001");
        registerAndSubmitSubVersion("2026.10.33.001", "dup", "1.0.0", List.of("a/b:1", "a_b:1"));

        ResponseEntity<String> collided = createPackageJobRaw("2026.10.33.001", List.of("a/b:1", "a_b:1"));

        assertThat(collided.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(collided.getBody()).contains("E-0301").contains("fileNameCollision");
    }

    @Test
    void DONE_Job은_차단되고_FAILED_Job은_재실행이_허용된다() {
        registerMainVersion("2026.11.01.001");
        registerAndSubmitSubVersion("2026.11.01.001", "pips", "1.0.0", null);
        insertPackageJob("2026.11.01.001", "DONE", "https://contoso.sharepoint.com/2026.11.01", null);

        ResponseEntity<String> blocked = createPackageJobRaw("2026.11.01.001", List.of("pips:1.0.0"));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody()).contains("E-0302");
        // 구현계획서 402행 — 차단 응답이 기존 Job 정보(공유 링크)를 실어야 호출측이
        // 새로 만들지 않고도 기존 결과를 알 수 있다.
        assertThat(blocked.getBody()).contains("https://contoso.sharepoint.com/2026.11.01");

        jdbcTemplate.update("UPDATE package_job SET status = 'FAILED' WHERE version_name = ?", "2026.11.01.001");

        ResponseEntity<PackageJobDetailResponse> retried = createPackageJob("2026.11.01.001", List.of("pips:1.0.0"));
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retried.getBody().job().status()).isEqualTo("PENDING");
    }

    /** 완료된 Job을 다시 돌리는 유일한 경로다 — 패키지를 정리(DELETED)하면 재생성이 열린다. */
    @Test
    void 정리된_Job을_재생성하면_공유링크는_유지되고_시각이_초기화된다() {
        registerMainVersion("2026.11.11.001");
        registerAndSubmitSubVersion("2026.11.11.001", "pips", "1.0.0", null);
        String folderUrl = "https://contoso.sharepoint.com/2026.11.11";
        insertPackageJob("2026.11.11.001", "DELETED", folderUrl, null);
        // 7일 전으로 밀어 둔다 — created_at이 DATETIME(초 단위)이라 같은 초에 재생성하면
        // 갱신 여부를 구분할 수 없다. 서버에서 실제로 난 증상(163시간 표기)과 같은 모양이다.
        jdbcTemplate.update(
                "UPDATE package_job SET created_at = DATE_SUB(NOW(), INTERVAL 7 DAY) WHERE version_name = ?",
                "2026.11.11.001");
        // API로 먼저 조회해 비교 기준을 잡는다 — JDBC 직접 조회(java.sql.Timestamp)와
        // Hibernate의 Instant 매핑은 MySQL DATETIME(타임존 정보 없음)을 변환하는 경로가
        // 달라 값이 갈릴 수 있다. 같은 경로(API 응답)로 얻은 값끼리만 비교해야 안전하다.
        Instant originalCreatedAt = restTemplate
                .getForEntity("/api/package-jobs/{versionName}", PackageJobDetailResponse.class, "2026.11.11.001")
                .getBody()
                .job()
                .createdAt();

        ResponseEntity<PackageJobDetailResponse> recreated = createPackageJob("2026.11.11.001", List.of("pips:1.0.0"));

        assertThat(recreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(recreated.getBody().job().status()).isEqualTo("PENDING");
        assertThat(recreated.getBody().job().spFolderUrl()).isEqualTo(folderUrl);
        assertThat(recreated.getBody().job().finishedAt()).isNull();
        // createdAt은 '요청 시각'이라 재생성하면 이번 요청 시각으로 갱신된다 — 안 그러면
        // 최초 생성 시각이 남아 소요 시간이 며칠짜리로 표기된다.
        assertThat(recreated.getBody().job().createdAt()).isAfter(originalCreatedAt);
        // 응답만 보면 안 된다 — 컬럼이 updatable=false면 메모리 대입은 성공하고 UPDATE에서만 빠져
        // 응답에는 새 값이, DB에는 옛 값이 남는다. 비교는 SQL 안에서 해 JDBC/Hibernate 변환 경로 차이를 피한다.
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT created_at > DATE_SUB(NOW(), INTERVAL 1 DAY) FROM package_job WHERE version_name = ?",
                        Boolean.class,
                        "2026.11.11.001"))
                .isTrue();
    }

    @Test
    void 진행_중인_Job은_재생성이_차단된다() {
        registerMainVersion("2026.11.21.001");
        registerAndSubmitSubVersion("2026.11.21.001", "pips", "1.0.0", null);
        insertPackageJob("2026.11.21.001", "DOWNLOADING", null, null);

        ResponseEntity<String> response = createPackageJobRaw("2026.11.21.001", List.of("pips:1.0.0"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("E-0302");
    }

    /** 살아 있는 산출물을 덮어쓰지 않는다 — 되돌리려면 패키지를 먼저 정리해야 한다. */
    @Test
    void 완료된_Job은_재생성이_차단된다() {
        registerMainVersion("2026.11.22.001");
        registerAndSubmitSubVersion("2026.11.22.001", "pips", "1.0.0", null);
        insertPackageJob("2026.11.22.001", "DONE", "https://contoso.sharepoint.com/2026.11.22", null);

        ResponseEntity<String> response = createPackageJobRaw("2026.11.22.001", List.of("pips:1.0.0"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("E-0302");
    }

    @Test
    void 동일_메인버전_동시_요청은_1건만_성공한다() throws InterruptedException {
        registerMainVersion("2026.11.31.001");
        registerAndSubmitSubVersion("2026.11.31.001", "pips", "1.0.0", null);

        int threadCount = 2;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        Runnable task = () -> {
            try {
                barrier.await();
                ResponseEntity<String> response = createPackageJobRaw("2026.11.31.001", List.of("pips:1.0.0"));
                if (response.getStatusCode() == HttpStatus.CREATED) {
                    successCount.incrementAndGet();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertThat(successCount.get()).isEqualTo(1);
    }

    @Test
    void 기존_FAILED_Job의_동시_재실행_요청도_1건만_성공한다() throws InterruptedException {
        // 신규 INSERT 경합(PK 유니크 제약)과는 다른 경로다 — 기존 행 재사용은 UPDATE라
        // 비관적 락(findByVersionName)이 직렬화의 유일한 방어선이다. resolveJob이 락
        // 획득 전에 findById로 엔티티를 먼저 적재해버리면, 락은 DB에서는 걸리지만
        // Hibernate가 1차 캐시에 있던 stale 인스턴스를 그대로 반환해 두 요청 모두 같은
        // (오래된) FAILED 상태를 보고 통과할 수 있다 — 이 테스트가 그 경로를 잡는다.
        registerMainVersion("2026.11.32.001");
        registerAndSubmitSubVersion("2026.11.32.001", "pips", "1.0.0", null);
        insertPackageJob("2026.11.32.001", "FAILED", null, null);

        int threadCount = 2;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        Runnable task = () -> {
            try {
                barrier.await();
                ResponseEntity<String> response = createPackageJobRaw("2026.11.32.001", List.of("pips:1.0.0"));
                if (response.getStatusCode() == HttpStatus.CREATED) {
                    successCount.incrementAndGet();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertThat(successCount.get()).isEqualTo(1);
    }

    @Test
    void 기동_시_고아_Job을_FAILED로_정리한다() {
        // PENDING도 포함한다 — waitForTasksToCompleteOnShutdown을 켜지 않아 큐에서 대기
        // 중이던 Job은 재기동하면 사라진다. 빠뜨리면 그 메인버전은 영원히
        // 복구 불가능해진다(OrphanJobCleaner 클래스 javadoc 참고).
        registerMainVersion("2026.12.01.001");
        insertPackageJob("2026.12.01.001", "DOWNLOADING", null, null);
        registerMainVersion("2026.12.02.001");
        insertPackageJob("2026.12.02.001", "PENDING", null, null);

        orphanJobCleaner.run(new DefaultApplicationArguments());

        assertThat(queryStatus("2026.12.01.001")).isEqualTo("FAILED");
        assertThat(queryStatus("2026.12.02.001")).isEqualTo("FAILED");
    }

    private void registerMainVersion(String versionName) {
        ResponseEntity<MainVersionInfoResponse> response = restTemplate.postForEntity(
                "/api/main-versions", new MainVersionCreateRequest(versionName, null, null), MainVersionInfoResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void registerAndSubmitSubVersion(String versionName, String code, String version, List<String> imageTags) {
        // 값 등록과 제출을 한 요청으로 한다 — 상태는 요청 본문이 선언한다.
        // null은 "기본 태그" 뜻이다 — 서비스의 code:version 자동생성이 없어져 픽스처가 직접 만든다.
        List<String> tags = imageTags == null ? List.of("%s:%s".formatted(code, version)) : imageTags;
        putSubVersion(versionName, new SubVersionUpsertRequest(code, version, null, 1, SubmitStatus.UPDATED, tags));
    }

    private ResponseEntity<SubVersionSavedResponse> putSubVersion(String versionName, SubVersionUpsertRequest item) {
        ResponseEntity<SubVersionSavedResponse> response = restTemplate.exchange(
                "/api/main-versions/{versionName}/sub-versions/{code}",
                HttpMethod.PUT,
                new HttpEntity<>(item),
                SubVersionSavedResponse.class,
                versionName,
                item.code());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private ResponseEntity<PackageJobDetailResponse> createPackageJob(
            String versionName, List<String> imageTags) {
        return restTemplate.postForEntity(
                "/api/main-versions/{versionName}/package-job",
                new PackageJobCreateRequest(imageTags),
                PackageJobDetailResponse.class,
                versionName);
    }

    private ResponseEntity<String> createPackageJobRaw(String versionName, List<String> imageTags) {
        return restTemplate.postForEntity(
                "/api/main-versions/{versionName}/package-job",
                new PackageJobCreateRequest(imageTags),
                String.class,
                versionName);
    }

    private void insertPackageJob(String versionName, String status, String folderUrl, String folderId) {
        jdbcTemplate.update(
                "INSERT INTO package_job (version_name, status, sp_folder_url, sp_folder_id) "
                        + "VALUES (?, ?, ?, ?)",
                versionName,
                status,
                folderUrl,
                folderId);
    }

    private String queryStatus(String versionName) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM package_job WHERE version_name = ?", String.class, versionName);
    }
}
