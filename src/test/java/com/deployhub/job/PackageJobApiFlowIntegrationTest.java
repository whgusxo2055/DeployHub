package com.deployhub.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.deployhub.job.dto.PackageItemResponse;
import com.deployhub.job.dto.PackageJobCreateRequest;
import com.deployhub.job.dto.PackageJobDetailResponse;
import com.deployhub.job.service.OrphanJobCleaner;
import com.deployhub.support.MySqlContainerSupport;
import com.deployhub.version.dto.MainVersionCreateRequest;
import com.deployhub.version.dto.MainVersionInfoResponse;
import com.deployhub.version.dto.SubVersionSavedResponse;
import com.deployhub.version.dto.SubVersionUpsertBatchRequest;
import com.deployhub.version.dto.SubVersionUpsertRequest;
import com.deployhub.version.dto.SubmitStatusChangeRequest;
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
        jdbcTemplate.execute("DELETE FROM package_item");
        jdbcTemplate.execute("DELETE FROM package_job");
        jdbcTemplate.execute("DELETE FROM sub_version");
        jdbcTemplate.execute("DELETE FROM main_version");
    }

    @Test
    void 변경된_컴포넌트만_선택되고_오케스트레이터가_실행된다() {
        registerMainVersion("2026.10.01");
        registerAndSubmitSubVersion("2026.10.01", "pips", "1.0.0", null);

        registerMainVersion("2026.10.02");
        registerAndSubmitSubVersion("2026.10.02", "pips", "1.0.0", null); // 직전과 동일 → 미변경
        registerAndSubmitSubVersion("2026.10.02", "api", "2.0.0", null); // 신규 → 변경

        ResponseEntity<String[]> changed = restTemplate.getForEntity(
                "/api/main-versions/{versionName}/changed-components", String[].class, "2026.10.02");
        assertThat(changed.getBody()).containsExactly("api:2.0.0");

        ResponseEntity<PackageJobDetailResponse> created = createPackageJob("2026.10.02", null, "tester", false);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().items()).extracting(PackageItemResponse::imageTag).containsExactly("api:2.0.0");

        // placeholder NCR 엔드포인트라 VALIDATING을 실제로 시도하다 FAILED로 끝난다 —
        // "DONE까지 도달"은 실 레지스트리를 쓰는 PackageJobDownloadFlowIntegrationTest가 검증한다.
        // 여기서는 오케스트레이터가 정말 PENDING을 벗어나 실행됐다는 것만 확인한다.
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    ResponseEntity<PackageJobDetailResponse> polled = restTemplate.getForEntity(
                            "/api/package-jobs/{versionName}", PackageJobDetailResponse.class, "2026.10.02");
                    assertThat(polled.getBody().job().status()).isEqualTo("FAILED");
                    assertThat(polled.getBody().items()).extracting(PackageItemResponse::imageTag)
                            .containsExactly("api:2.0.0");
                });
    }

    @Test
    void 미변경_컴포넌트만_명시해도_부분_패키징된다() {
        registerMainVersion("2026.10.41");
        registerAndSubmitSubVersion("2026.10.41", "pips", "1.0.0", null);

        registerMainVersion("2026.10.42");
        registerAndSubmitSubVersion("2026.10.42", "pips", "1.0.0", null); // 직전과 동일 → 미변경
        registerAndSubmitSubVersion("2026.10.42", "api", "2.0.0", null); // 신규 → 변경

        // 선택 범위는 "변경분"이 아니라 "메인버전의 전체 컴포넌트"다 — 기본값에 없는 미변경분만
        // 골라도 통과해야 한다. 변경분(api:2.0.0)이 함께 딸려 들어가서도 안 된다.
        ResponseEntity<PackageJobDetailResponse> created =
                createPackageJob("2026.10.42", List.of("pips:1.0.0"), "tester", false);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().items())
                .extracting(PackageItemResponse::imageTag)
                .containsExactly("pips:1.0.0");
    }

    @Test
    void 직전_메인버전과_전건_동일하면_E_0303으로_거부되지만_명시_선택은_허용된다() {
        registerMainVersion("2026.10.11");
        registerAndSubmitSubVersion("2026.10.11", "pips", "1.0.0", null);

        registerMainVersion("2026.10.12");
        registerAndSubmitSubVersion("2026.10.12", "pips", "1.0.0", null); // 완전히 동일

        ResponseEntity<String> response = createPackageJobRaw("2026.10.12", null, "tester", false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("E-0303");

        // 거부 기준은 "변경분 0건"이 아니라 "선택 0건"이다 — 변경분이 하나도 없어도
        // 호출측이 직접 지정하면 반입할 수 있어야 한다.
        ResponseEntity<PackageJobDetailResponse> explicit =
                createPackageJob("2026.10.12", List.of("pips:1.0.0"), "tester", false);

        assertThat(explicit.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(explicit.getBody().items())
                .extracting(PackageItemResponse::imageTag)
                .containsExactly("pips:1.0.0");
    }

    @Test
    void PENDING_서브버전이_남아있으면_E_0305로_거부된다() {
        registerMainVersion("2026.10.21");
        // registerAndSubmitSubVersion을 쓰지 않고 submit-status 변경을 생략 → PENDING 유지.
        putSubVersions("2026.10.21", new SubVersionUpsertRequest("pips", "1.0.0", null, 1, null));

        ResponseEntity<String> response = createPackageJobRaw("2026.10.21", null, "tester", false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("E-0305");
    }

    @Test
    void 없는_태그나_중복_태그를_지정하면_E_0301로_거부된다() {
        registerMainVersion("2026.10.31");
        registerAndSubmitSubVersion("2026.10.31", "pips", "1.0.0", null);

        ResponseEntity<String> unknownTag =
                createPackageJobRaw("2026.10.31", List.of("not-exist:1.0"), "tester", false);
        assertThat(unknownTag.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknownTag.getBody()).contains("E-0301");

        ResponseEntity<String> duplicateTag =
                createPackageJobRaw("2026.10.31", List.of("pips:1.0.0", "pips:1.0.0"), "tester", false);
        assertThat(duplicateTag.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(duplicateTag.getBody()).contains("E-0301");
    }

    @Test
    void DONE_Job은_차단되고_FAILED_Job은_재실행이_허용된다() {
        registerMainVersion("2026.11.01");
        registerAndSubmitSubVersion("2026.11.01", "pips", "1.0.0", null);
        insertPackageJob("2026.11.01", "DONE", "https://contoso.sharepoint.com/2026.11.01", null, "tester");

        ResponseEntity<String> blocked = createPackageJobRaw("2026.11.01", null, "tester", false);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody()).contains("E-0302");
        // 구현계획서 402행 — 차단 응답이 기존 Job 정보(공유 링크)를 실어야 호출측이
        // 새로 만들지 않고도 기존 결과를 알 수 있다.
        assertThat(blocked.getBody()).contains("https://contoso.sharepoint.com/2026.11.01");

        jdbcTemplate.update("UPDATE package_job SET status = 'FAILED' WHERE version_name = ?", "2026.11.01");

        ResponseEntity<PackageJobDetailResponse> retried = createPackageJob("2026.11.01", null, "retrier", false);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retried.getBody().job().status()).isEqualTo("PENDING");
    }

    @Test
    void force로_DONE_Job을_재생성하면_공유링크는_유지되고_finishedAt은_초기화된다() {
        registerMainVersion("2026.11.11");
        registerAndSubmitSubVersion("2026.11.11", "pips", "1.0.0", null);
        String folderUrl = "https://contoso.sharepoint.com/2026.11.11";
        insertPackageJob("2026.11.11", "DONE", folderUrl, null, "original-user");
        // API로 먼저 조회해 비교 기준을 잡는다 — JDBC 직접 조회(java.sql.Timestamp)와
        // Hibernate의 Instant 매핑은 MySQL DATETIME(타임존 정보 없음)을 변환하는 경로가
        // 달라 값이 갈릴 수 있다. 같은 경로(API 응답)로 얻은 값끼리만 비교해야 안전하다.
        Instant originalCreatedAt = restTemplate
                .getForEntity("/api/package-jobs/{versionName}", PackageJobDetailResponse.class, "2026.11.11")
                .getBody()
                .job()
                .createdAt();

        ResponseEntity<PackageJobDetailResponse> forced = createPackageJob("2026.11.11", null, "new-user", true);

        assertThat(forced.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(forced.getBody().job().status()).isEqualTo("PENDING");
        assertThat(forced.getBody().job().spFolderUrl()).isEqualTo(folderUrl);
        assertThat(forced.getBody().job().finishedAt()).isNull();
        // createdBy는 재실행 요청자로 바뀌지만 createdAt(최초 생성 시각)은 그대로다 —
        // 컬럼이 updatable=false라 resetForRerun이 건드리면 응답과 DB가 어긋난다.
        assertThat(forced.getBody().job().createdBy()).isEqualTo("new-user");
        assertThat(forced.getBody().job().createdAt()).isEqualTo(originalCreatedAt);
    }

    @Test
    void 진행_중인_Job은_force로도_차단된다() {
        registerMainVersion("2026.11.21");
        registerAndSubmitSubVersion("2026.11.21", "pips", "1.0.0", null);
        insertPackageJob("2026.11.21", "DOWNLOADING", null, null, "tester");

        ResponseEntity<String> response = createPackageJobRaw("2026.11.21", null, "tester", true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("E-0302");
    }

    @Test
    void 동일_메인버전_동시_요청은_1건만_성공한다() throws InterruptedException {
        registerMainVersion("2026.11.31");
        registerAndSubmitSubVersion("2026.11.31", "pips", "1.0.0", null);

        int threadCount = 2;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        Runnable task = () -> {
            try {
                barrier.await();
                ResponseEntity<String> response = createPackageJobRaw("2026.11.31", null, "tester", false);
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
        registerMainVersion("2026.11.32");
        registerAndSubmitSubVersion("2026.11.32", "pips", "1.0.0", null);
        insertPackageJob("2026.11.32", "FAILED", null, null, "tester");

        int threadCount = 2;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        Runnable task = () -> {
            try {
                barrier.await();
                ResponseEntity<String> response = createPackageJobRaw("2026.11.32", null, "tester", false);
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
        // 중이던 Job은 재기동하면 사라진다. 빠뜨리면 그 메인버전은 force로도 영원히
        // 복구 불가능해진다(OrphanJobCleaner 클래스 javadoc 참고).
        registerMainVersion("2026.12.01");
        insertPackageJob("2026.12.01", "DOWNLOADING", null, null, "tester");
        registerMainVersion("2026.12.02");
        insertPackageJob("2026.12.02", "PENDING", null, null, "tester");

        orphanJobCleaner.run(new DefaultApplicationArguments());

        assertThat(queryStatus("2026.12.01")).isEqualTo("FAILED");
        assertThat(queryStatus("2026.12.02")).isEqualTo("FAILED");
    }

    private void registerMainVersion(String versionName) {
        ResponseEntity<MainVersionInfoResponse> response = restTemplate.postForEntity(
                "/api/main-versions", new MainVersionCreateRequest(versionName, null, null), MainVersionInfoResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void registerAndSubmitSubVersion(String versionName, String code, String version, List<String> imageTags) {
        ResponseEntity<SubVersionSavedResponse[]> saved =
                putSubVersions(versionName, new SubVersionUpsertRequest(code, version, null, 1, imageTags));
        Long id = saved.getBody()[0].id();
        restTemplate.exchange(
                "/api/sub-versions/{id}/submit-status",
                HttpMethod.PATCH,
                new HttpEntity<>(new SubmitStatusChangeRequest(SubmitStatus.UPDATED)),
                Void.class,
                id);
    }

    private ResponseEntity<SubVersionSavedResponse[]> putSubVersions(String versionName, SubVersionUpsertRequest item) {
        ResponseEntity<SubVersionSavedResponse[]> response = restTemplate.exchange(
                "/api/main-versions/{versionName}/sub-versions",
                HttpMethod.PUT,
                new HttpEntity<>(new SubVersionUpsertBatchRequest(List.of(item))),
                SubVersionSavedResponse[].class,
                versionName);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private ResponseEntity<PackageJobDetailResponse> createPackageJob(
            String versionName, List<String> imageTags, String createdBy, boolean force) {
        return restTemplate.postForEntity(
                "/api/main-versions/{versionName}/package-job",
                new PackageJobCreateRequest(imageTags, createdBy, force),
                PackageJobDetailResponse.class,
                versionName);
    }

    private ResponseEntity<String> createPackageJobRaw(
            String versionName, List<String> imageTags, String createdBy, boolean force) {
        return restTemplate.postForEntity(
                "/api/main-versions/{versionName}/package-job",
                new PackageJobCreateRequest(imageTags, createdBy, force),
                String.class,
                versionName);
    }

    private void insertPackageJob(String versionName, String status, String folderUrl, String folderId, String createdBy) {
        jdbcTemplate.update(
                "INSERT INTO package_job (version_name, status, created_by, sp_folder_url, sp_folder_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                versionName,
                status,
                createdBy,
                folderUrl,
                folderId);
    }

    private String queryStatus(String versionName) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM package_job WHERE version_name = ?", String.class, versionName);
    }
}
