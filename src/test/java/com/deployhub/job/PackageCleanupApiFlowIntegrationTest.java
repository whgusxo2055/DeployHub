package com.deployhub.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.job.dto.PackageCleanupResponse;
import com.deployhub.job.dto.PackageFileResponse;
import com.deployhub.job.dto.PackageFilesResponse;
import com.deployhub.job.dto.PackageJobDetailResponse;
import com.deployhub.job.repository.PackageJobRepository;
import com.deployhub.job.service.PackagePurgeService;
import com.deployhub.support.MySqlContainerSupport;
import com.deployhub.version.dto.MainVersionCreateRequest;
import com.deployhub.version.dto.MainVersionInfoResponse;
import com.deployhub.version.dto.SubVersionSavedResponse;
import com.deployhub.version.dto.SubVersionUpsertRequest;
import com.deployhub.version.entity.SubmitStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.util.FileSystemUtils;

/**
 * Phase 6 완료 기준(구현계획서 613-618행) — FN-10 파일 URL 목록과 FN-11 보존·정리를
 * 실제 MySQL 컨테이너 위에서 HTTP 엔드투엔드로 검증한다.
 *
 * <p>{@code sp_folder_id}를 비운 Job만 쓴다 — 그러면 {@code PackagePurgeService}가 Graph
 * 삭제 호출을 건너뛰므로 SharePoint 목 없이도 대상 선정·{@code deleted_at} 기록·로컬
 * 디렉터리 삭제라는 이 Phase의 실제 로직을 그대로 태울 수 있다. Graph 폴더 삭제 자체는
 * {@code GraphApiClient.delete}(Phase 5에서 이미 검증)의 한 줄 호출이다.
 */
class PackageCleanupApiFlowIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 재실행 감지는 HTTP로 재현할 수 없다(배치 진입과 삭제 사이를 갈라야 한다) — 직접 부른다. */
    @Autowired
    private PackagePurgeService packagePurgeService;

    @Autowired
    private PackageJobRepository packageJobRepository;

    @Value("${deployhub.work-dir}")
    private String workDir;

    /**
     * 기한을 최소값(1일)으로 낮춘다 — 픽스처를 전부 하루보다 오래된 시각으로 넣어 <b>전건이
     * 기한 경과</b>가 되게 하면, 이 테스트가 검증하는 대상이 시간 계산이 아니라 <b>보호
     * 규칙</b>(최근 count건 제외)으로 좁혀진다. count=1이라 가장 최근 DONE 1건만 살아남아야 한다.
     *
     * <p>0을 못 쓰는 이유는 {@code PackageCleanupService} 생성자가 {@code days < 1}을 기동
     * 단계에서 막기 때문이다 — 오타 하나가 전량 삭제로 이어지는 값이라 의도된 제약이다.
     */
    @DynamicPropertySource
    static void 즉시_만료되는_보존정책(DynamicPropertyRegistry registry) {
        registry.add("deployhub.retention.days", () -> 1);
        registry.add("deployhub.retention.count", () -> 1);
        registry.add("deployhub.retention.local-cleanup-delay-hours", () -> 0);
    }

    @AfterEach
    void 데이터_정리() throws IOException {
        jdbcTemplate.execute("DELETE FROM package_item");
        jdbcTemplate.execute("DELETE FROM package_job");
        jdbcTemplate.execute("DELETE FROM sub_version");
        jdbcTemplate.execute("DELETE FROM main_version");
        FileSystemUtils.deleteRecursively(Path.of(workDir));
    }

    @Test
    void 완료된_Job은_폴더_공유링크와_파일별_URL을_돌려준다() {
        registerMainVersion("2027.01.01");
        registerSubVersion("2027.01.01", "cc", "v2.0.25", List.of("sb-cc-api:v2.0.25.8612"));
        String folderUrl = "https://contoso.sharepoint.com/:f:/s/2027.01.01";
        insertDoneJob("2027.01.01", folderUrl, Instant.now().minus(Duration.ofDays(1)));
        insertUploadedItem("2027.01.01", "sb-cc-api:v2.0.25.8612", 1_234L, "https://contoso.sharepoint.com/api.tar");

        ResponseEntity<PackageFilesResponse> response = restTemplate.getForEntity(
                "/api/package-jobs/{versionName}/files", PackageFilesResponse.class, "2027.01.01");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().folderUrl()).isEqualTo(folderUrl);
        assertThat(response.getBody().deletedAt()).isNull();
        PackageFileResponse file = response.getBody().files().get(0);
        assertThat(file.imageTag()).isEqualTo("sb-cc-api:v2.0.25.8612");
        assertThat(file.fileUrl()).isEqualTo("https://contoso.sharepoint.com/api.tar");
        assertThat(file.fileSize()).isEqualTo(1_234L);
        // Phase 4가 만든 .tar 파일명과 같은 규칙(ImageReference.tarFileName)이어야 한다.
        assertThat(file.fileName()).isEqualTo("sb-cc-api_v2.0.25.8612.tar");
        // image_tag → component → sub_version 역참조 (구현계획서 589행).
        assertThat(file.subVersionCode()).isEqualTo("cc");
        assertThat(file.subVersionVersion()).isEqualTo("v2.0.25");
    }

    @Test
    void 미완료_Job의_파일_목록은_E_1201과_진행_상태를_돌려준다() {
        registerMainVersion("2027.01.02");
        registerSubVersion("2027.01.02", "pips", "1.0.0", null);
        jdbcTemplate.update(
                "INSERT INTO package_job (version_name, status) VALUES (?, 'DOWNLOADING')",
                "2027.01.02");

        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/package-jobs/{versionName}/files", String.class, "2027.01.02");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("E-1201").contains("DOWNLOADING");
    }

    @Test
    void dryRun은_기한_경과_Job만_고르고_최근_RETENTION_COUNT건은_보호한다() throws IOException {
        Instant now = Instant.now();
        insertDoneJobWithFolderAndLocalDir("2027.02.01", now.minus(Duration.ofDays(400)));
        insertDoneJobWithFolderAndLocalDir("2027.02.02", now.minus(Duration.ofDays(300)));
        insertDoneJobWithFolderAndLocalDir("2027.02.03", now.minus(Duration.ofDays(2))); // 기한은 지났지만 최근 1건 → 보호

        ResponseEntity<PackageCleanupResponse> response =
                restTemplate.postForEntity("/api/admin/cleanup?dryRun=true", null, PackageCleanupResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().dryRun()).isTrue();
        assertThat(response.getBody().sharePointCleaned()).containsExactlyInAnyOrder("2027.02.01", "2027.02.02");
        // 중계 서버 정리에는 보호 규칙이 없다 — 업로드가 끝난 뒤 유예만 지나면 전건 대상이다.
        assertThat(response.getBody().localCleaned())
                .containsExactlyInAnyOrder("2027.02.01", "2027.02.02", "2027.02.03");
        // dry_run은 실제로 지우지 않는다.
        assertThat(Files.isDirectory(Path.of(workDir, "2027.02.01"))).isTrue();
        assertThat(queryDeletedAt("2027.02.01")).isNull();
    }

    @Test
    void 정리_실행은_디렉터리와_deletedAt을_남기고_Job_이력은_유지한다() throws IOException {
        insertDoneJobWithLocalDir("2027.03.01", Instant.now().minus(Duration.ofDays(400)));
        insertDoneJobWithLocalDir("2027.03.02", Instant.now().minus(Duration.ofDays(2))); // 기한 경과지만 최근 1건 → 보호

        ResponseEntity<PackageCleanupResponse> response =
                restTemplate.postForEntity("/api/admin/cleanup?dryRun=false", null, PackageCleanupResponse.class);

        // 2단계가 이 Job을 처리했다는 증거는 deleted_at이다. sharePointCleaned에는 올라오지
        // 않는다 — 픽스처에 sp_folder_id가 없어 실제로 지운 폴더가 없기 때문이다. 안 지운
        // 폴더를 "정리 완료"로 세면 운영자가 상태를 오판하므로 cleanupOne과 같은 규칙을 쓴다.
        assertThat(response.getBody().sharePointCleaned()).isEmpty();
        // 1단계에는 보호 규칙이 없어 03.02의 작업 디렉터리도 함께 지워진다 — 보호되는 건
        // SharePoint 폴더(2단계)뿐이고, 그 증거는 아래 deleted_at이다.
        assertThat(response.getBody().localCleaned()).containsExactlyInAnyOrder("2027.03.01", "2027.03.02");
        assertThat(response.getBody().failed()).isEmpty();
        assertThat(Files.exists(Path.of(workDir, "2027.03.01"))).isFalse();
        assertThat(queryDeletedAt("2027.03.01")).isNotNull();
        assertThat(queryDeletedAt("2027.03.02")).isNull();

        // 구현계획서 597행 — 행 자체는 지우지 않아 정리 후에도 이력 조회가 된다.
        ResponseEntity<PackageJobDetailResponse> history = restTemplate.getForEntity(
                "/api/package-jobs/{versionName}", PackageJobDetailResponse.class, "2027.03.01");
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody().job().status()).isEqualTo("DONE");
    }

    @Test
    void 수동_정리는_보호_규칙과_무관하게_즉시_지우고_진행_중_Job은_E_1404로_거부한다() throws IOException {
        // 방금 끝난 Job이라 배치라면 보호 대상이지만, 운영자가 지정하면 즉시 정리한다.
        insertDoneJobWithLocalDir("2027.04.01", Instant.now().minus(Duration.ofSeconds(1)));
        registerMainVersion("2027.04.02");
        jdbcTemplate.update(
                "INSERT INTO package_job (version_name, status) VALUES (?, 'UPLOADING')",
                "2027.04.02");

        ResponseEntity<PackageCleanupResponse> deleted = restTemplate.exchange(
                "/api/package-jobs/{versionName}/package",
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                PackageCleanupResponse.class,
                "2027.04.01");

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Files.exists(Path.of(workDir, "2027.04.01"))).isFalse();
        assertThat(queryDeletedAt("2027.04.01")).isNotNull();
        // 응답은 실제로 지운 것만 보고한다 — sp_folder_id가 없어 SharePoint 폴더는 지운 게 없다.
        assertThat(deleted.getBody().localCleaned()).containsExactly("2027.04.01");
        assertThat(deleted.getBody().sharePointCleaned()).isEmpty();
        assertThat(deleted.getBody().failed()).isEmpty();

        ResponseEntity<String> blocked = restTemplate.exchange(
                "/api/package-jobs/{versionName}/package",
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                String.class,
                "2027.04.02");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody()).contains("E-1404");
    }

    @Test
    void dryRun도_실제로_지울_것만_보고한다() {
        // 디렉터리 없음 + 폴더 id 없음 = 지울 게 하나도 없는 Job. dryRun이 이걸 담으면
        // 운영자가 "내일 지워지겠구나"로 읽지만 실제 실행은 아무것도 안 한다.
        registerMainVersion("2027.09.01");
        insertDoneJob("2027.09.01", null, Instant.now().minus(Duration.ofDays(400)));

        ResponseEntity<PackageCleanupResponse> response =
                restTemplate.postForEntity("/api/admin/cleanup?dryRun=true", null, PackageCleanupResponse.class);

        assertThat(response.getBody().localCleaned()).isEmpty();
        assertThat(response.getBody().sharePointCleaned()).isEmpty();
    }

    @Test
    void dryRun은_폴더가_없어도_지워질_로컬_디렉터리는_보고한다() throws IOException {
        // 1단계는 DONE만 훑으므로 FAILED Job의 디렉터리는 2단계가 폴더와 함께 지운다 —
        // dryRun이 폴더 id만 보면 실제로 지워질 수 GB가 보고에서 통째로 빠진다.
        registerMainVersion("2027.09.11");
        insertJob("2027.09.11", "FAILED", null, null, Instant.now().minus(Duration.ofDays(400)));
        Files.createDirectories(Path.of(workDir, "2027.09.11", "images"));

        ResponseEntity<PackageCleanupResponse> response =
                restTemplate.postForEntity("/api/admin/cleanup?dryRun=true", null, PackageCleanupResponse.class);

        assertThat(response.getBody().localCleaned()).containsExactly("2027.09.11");
        assertThat(response.getBody().sharePointCleaned()).isEmpty();
    }

    @Test
    void 이미_사라진_작업_디렉터리는_정리했다고_보고하지_않는다() {
        // 1단계는 deleted_at을 안 찍어 대상에서 안 빠지므로, 같은 Job이 매일 다시 선정된다.
        // 디렉터리가 이미 없으면 지운 게 없는데도 localCleaned에 실려 운영자가 오판한다(실측).
        registerMainVersion("2027.08.01");
        insertDoneJob("2027.08.01", null, Instant.now().minus(Duration.ofDays(2)));

        ResponseEntity<PackageCleanupResponse> response =
                restTemplate.postForEntity("/api/admin/cleanup?dryRun=false", null, PackageCleanupResponse.class);

        assertThat(response.getBody().localCleaned()).isEmpty();
        // 없는 것은 실패도 아니다 — 재시도해도 달라질 게 없다.
        assertThat(response.getBody().failed()).isEmpty();
    }

    @Test
    void 이미_정리된_Job을_다시_지우면_아무것도_지웠다고_하지_않는다() throws IOException {
        insertDoneJobWithLocalDir("2027.08.11", Instant.now().minus(Duration.ofDays(2)));

        ResponseEntity<PackageCleanupResponse> first = deletePackage("2027.08.11");
        assertThat(first.getBody().localCleaned()).containsExactly("2027.08.11");
        Instant 최초_정리_시각 = queryDeletedAt("2027.08.11").toInstant();

        ResponseEntity<PackageCleanupResponse> second = deletePackage("2027.08.11");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().localCleaned()).isEmpty();
        assertThat(second.getBody().sharePointCleaned()).isEmpty();
        assertThat(second.getBody().failed()).isEmpty();
        // 재호출이 deleted_at을 덮어쓰면 "언제 정리했나"라는 감사 흔적이 사라진다.
        assertThat(queryDeletedAt("2027.08.11").toInstant()).isEqualTo(최초_정리_시각);
    }

    private ResponseEntity<PackageCleanupResponse> deletePackage(String versionName) {
        return restTemplate.exchange(
                "/api/package-jobs/{versionName}/package",
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                PackageCleanupResponse.class,
                versionName);
    }

    @Test
    void FAILED_Job의_SharePoint_폴더도_기한이_지나면_정리_대상이_된다() throws IOException {
        // FN-08이 UPLOADING 진입 시 폴더를 만들어 두고 업로드가 깨지면 Job은 FAILED로 끝난다 —
        // DONE만 훑으면 그 폴더와 부분 업로드분이 영구히 남는다(코드리뷰로 발견).
        registerMainVersion("2027.05.01");
        insertJob("2027.05.01", "FAILED", "https://contoso.sharepoint.com/2027.05.01", "folder-2027.05.01",
                Instant.now().minus(Duration.ofDays(400)));
        Files.createDirectories(Path.of(workDir, "2027.05.01", "images"));

        ResponseEntity<PackageCleanupResponse> response =
                restTemplate.postForEntity("/api/admin/cleanup?dryRun=true", null, PackageCleanupResponse.class);

        assertThat(response.getBody().sharePointCleaned()).containsExactly("2027.05.01");
        // 1단계(24시간 유예)는 DONE 전용이라 FAILED 디렉터리를 건드리지 않는다 — 대신 2단계가
        // 보존 기한 뒤에 폴더와 함께 지우므로(구현계획서 592행) dryRun도 그 삭제를 보고해야 한다.
        assertThat(response.getBody().localCleaned()).containsExactly("2027.05.01");
    }

    @Test
    void 진행_중인_Job은_기한이_지나도_정리_대상이_아니다() throws IOException {
        // 정리 경로가 종료 상태(DONE/FAILED)만 후보로 삼는다는 회귀 방어. 실제 삭제 직전의
        // 재확인은 PackagePurgeService가 행 락 안에서 한 번 더 한다.
        registerMainVersion("2027.06.01");
        insertJob("2027.06.01", "DOWNLOADING", null, Instant.now().minus(Duration.ofDays(400)));
        Files.createDirectories(Path.of(workDir, "2027.06.01", "images"));

        ResponseEntity<PackageCleanupResponse> response =
                restTemplate.postForEntity("/api/admin/cleanup?dryRun=false", null, PackageCleanupResponse.class);

        assertThat(response.getBody().localCleaned()).isEmpty();
        assertThat(response.getBody().sharePointCleaned()).isEmpty();
        assertThat(Files.isDirectory(Path.of(workDir, "2027.06.01"))).isTrue();
        assertThat(queryDeletedAt("2027.06.01")).isNull();
    }

    /**
     * 대상 선정은 배치 진입 시 뜬 스냅샷이라, 건별 삭제가 도는 동안 같은 메인버전이
     * {@code force=true}나 {@code /retry}로 다시 돌아 <b>완료까지 갈</b> 수 있다. 그때도 상태는
     * DONE이라 상태 검사만으로는 통과해 방금 만들어진 폴더·디렉터리를 지운다 —
     * {@code finished_at}이 그대로인지까지 봐야 잡힌다.
     */
    @Test
    void 대상_선정_이후_재실행된_Job은_finishedAt_불일치로_거부된다() throws IOException {
        insertDoneJobWithLocalDir("2027.07.01", Instant.now().minus(Duration.ofDays(400)));
        // 배치가 대상을 고를 때 본 값. 엔티티 매핑을 그대로 타야 purge 안의 비교와 같은
        // 변환 경로가 된다(CLAUDE.md — 원시 JDBC Timestamp와 값이 갈릴 수 있다).
        Instant 스냅샷_시점의_finishedAt = 조회한_finishedAt("2027.07.01");

        // 대상 선정 뒤 Job이 다시 돌아 방금 완료된 상황.
        jdbcTemplate.update(
                "UPDATE package_job SET finished_at = ? WHERE version_name = ?",
                Timestamp.from(Instant.now()),
                "2027.07.01");

        assertThatThrownBy(() -> packagePurgeService.purge("2027.07.01", "test", 스냅샷_시점의_finishedAt))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PACKAGE_CLEANUP_RERUN);
        assertThat(Files.isDirectory(Path.of(workDir, "2027.07.01"))).isTrue();
        assertThat(queryDeletedAt("2027.07.01")).isNull();

        // 값이 그대로면 통과한다 — 위 거부가 "항상 던진다"가 아님을 함께 고정한다.
        packagePurgeService.purge("2027.07.01", "test", 조회한_finishedAt("2027.07.01"));
        assertThat(Files.exists(Path.of(workDir, "2027.07.01"))).isFalse();
        assertThat(queryDeletedAt("2027.07.01")).isNotNull();
    }

    private void insertDoneJobWithFolderAndLocalDir(String versionName, Instant finishedAt) throws IOException {
        registerMainVersion(versionName);
        insertJob(versionName, "DONE", "https://contoso.sharepoint.com/" + versionName, "folder-" + versionName, finishedAt);
        Files.createDirectories(Path.of(workDir, versionName, "images"));
    }

    private void insertDoneJobWithLocalDir(String versionName, Instant finishedAt) throws IOException {
        registerMainVersion(versionName);
        insertDoneJob(versionName, null, finishedAt);
        Files.createDirectories(Path.of(workDir, versionName, "images"));
        Files.writeString(Path.of(workDir, versionName, "images", "dummy.tar"), "x");
    }

    private void registerMainVersion(String versionName) {
        ResponseEntity<MainVersionInfoResponse> response = restTemplate.postForEntity(
                "/api/main-versions",
                new MainVersionCreateRequest(versionName, null, null),
                MainVersionInfoResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void registerSubVersion(String versionName, String code, String version, List<String> imageTags) {
        ResponseEntity<SubVersionSavedResponse> response = restTemplate.exchange(
                "/api/main-versions/{versionName}/sub-versions/{code}",
                HttpMethod.PUT,
                new HttpEntity<>(new SubVersionUpsertRequest(code, version, null, 1, SubmitStatus.PENDING, imageTags)),
                SubVersionSavedResponse.class,
                versionName,
                code);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void insertDoneJob(String versionName, String folderUrl, Instant finishedAt) {
        insertJob(versionName, "DONE", folderUrl, finishedAt);
    }

    /** {@code sp_folder_id}는 비워 둔다 — Graph 호출 없이 정리 로직만 태우기 위함이다(클래스 javadoc). */
    private void insertJob(String versionName, String status, String folderUrl, Instant finishedAt) {
        insertJob(versionName, status, folderUrl, null, finishedAt);
    }

    /** dryRun은 Graph를 호출하지 않으므로 폴더 id를 줘도 안전하다 — 이제 그 값이 있어야 정리 대상으로 센다. */
    private void insertJob(String versionName, String status, String folderUrl, String folderId, Instant finishedAt) {
        jdbcTemplate.update(
                "INSERT INTO package_job (version_name, status, sp_folder_url, sp_folder_id, finished_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                versionName,
                status,
                folderUrl,
                folderId,
                Timestamp.from(finishedAt));
    }

    private void insertUploadedItem(String versionName, String imageTag, long fileSize, String fileUrl) {
        jdbcTemplate.update(
                "INSERT INTO package_item (version_name, image_tag, status, file_size, file_url) "
                        + "VALUES (?, ?, 'UPLOADED', ?, ?)",
                versionName,
                imageTag,
                fileSize,
                fileUrl);
    }

    private Instant 조회한_finishedAt(String versionName) {
        return packageJobRepository
                .findById(versionName)
                .orElseThrow()
                .getFinishedAt();
    }

    private Timestamp queryDeletedAt(String versionName) {
        return jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM package_job WHERE version_name = ?", Timestamp.class, versionName);
    }
}
