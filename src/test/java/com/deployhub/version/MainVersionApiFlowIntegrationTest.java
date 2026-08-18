package com.deployhub.version;

import static org.assertj.core.api.Assertions.assertThat;

import com.deployhub.version.dto.ComponentResponse;
import com.deployhub.version.dto.MainVersionCreateRequest;
import com.deployhub.version.dto.MainVersionDetailResponse;
import com.deployhub.version.dto.MainVersionInfoResponse;
import com.deployhub.version.dto.PackagingEligibilityResponse;
import com.deployhub.version.dto.SubVersionSavedResponse;
import com.deployhub.version.dto.SubVersionUpsertBatchRequest;
import com.deployhub.version.dto.SubVersionUpsertRequest;
import com.deployhub.version.dto.SubmitStatusChangeRequest;
import com.deployhub.version.entity.SubmitStatus;
import com.deployhub.support.MySqlContainerSupport;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 이 프로젝트는 프론트엔드가 범위에서 제외된 백엔드 전용 API 서버라 Playwright E2E가
 * 검증할 화면이 없다 (구현계획서 0.1절). 대신 Phase 1 완료 기준(구현계획서 328-334행)에
 * 명시된 핵심 흐름들을 실제 MySQL 컨테이너 위에서({@link MySqlContainerSupport}) HTTP
 * 엔드투엔드로 검증한다. 변경 여부 계산 로직 자체는
 * {@link com.deployhub.version.service.ChangeDetectorTest} 단위 테스트가 이미 덮으므로
 * 여기서 다시 검증하지 않는다.
 */
class MainVersionApiFlowIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 컨테이너·컨텍스트를 클래스 단위로 공유하고 @Transactional 롤백도 못 쓰므로
    // (RANDOM_PORT) 테스트 메서드마다 직접 비운다. package_item → package_job 순으로
    // 지운다 — FK가 ON DELETE CASCADE가 아니라(V1__init_schema.sql) 반대 순서면 위반한다.
    // component는 sub_version에 CASCADE가 걸려 있어 별도로 지우지 않아도 된다.
    @AfterEach
    void 데이터_정리() {
        jdbcTemplate.execute("DELETE FROM package_item");
        jdbcTemplate.execute("DELETE FROM package_job");
        jdbcTemplate.execute("DELETE FROM sub_version");
        jdbcTemplate.execute("DELETE FROM main_version");
    }

    /**
     * 매핑되지 않은 경로는 정적 리소스 조회를 거쳐 {@code NoResourceFoundException}이 되는데,
     * 이걸 안 잡으면 500 + 스택트레이스가 나가 클라이언트의 URL 오타가 서버 장애로 보고된다
     * (Phase 7 실서버 검증에서 발견).
     */
    @Test
    void 매핑되지_않은_경로는_500이_아니라_404_E_9001을_돌려준다() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/does-not-exist", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("E-9001");
    }

    @Test
    void 메인버전_등록부터_패키징_가능_여부_반영까지_전체_흐름이_동작한다() {
        // "2026.09.01"처럼 점을 포함한 경로 변수가 깨지지 않는지도 함께 검증한다
        // (구현계획서 321행 회귀 방지 요구).
        String versionName = "2026.09.01";

        // 1. 메인버전 등록 — UTF-8 한글 필드 왕복 확인
        ResponseEntity<MainVersionInfoResponse> created = restTemplate.postForEntity(
                "/api/main-versions",
                new MainVersionCreateRequest(versionName, "릴리즈 노트", null),
                MainVersionInfoResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().releaseNote()).isEqualTo("릴리즈 노트");

        // 2. 서브버전 등록 — 컴포넌트 미지정 → {code}:{version} 1건 자동 생성
        SubVersionUpsertBatchRequest batchRequest = new SubVersionUpsertBatchRequest(
                List.of(new SubVersionUpsertRequest("pips", "1.0.22.0300", "변경 사항", 1, null)));
        ResponseEntity<SubVersionSavedResponse[]> saved = restTemplate.exchange(
                "/api/main-versions/{versionName}/sub-versions",
                HttpMethod.PUT,
                new HttpEntity<>(batchRequest),
                SubVersionSavedResponse[].class,
                versionName);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody()).hasSize(1);
        SubVersionSavedResponse savedSubVersion = saved.getBody()[0];
        assertThat(savedSubVersion.imageTags()).containsExactly("pips:1.0.22.0300");

        // 3. 계층 조회 — 컴포넌트 자동 생성 확인
        ResponseEntity<MainVersionDetailResponse> detail = restTemplate.getForEntity(
                "/api/main-versions/{versionName}", MainVersionDetailResponse.class, versionName);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody().subVersions()).hasSize(1);
        assertThat(detail.getBody().subVersions().get(0).components())
                .extracting(ComponentResponse::imageTag)
                .containsExactly("pips:1.0.22.0300");

        // 4. 패키징 가능 여부 — submit_status가 PENDING이라 아직 불가
        ResponseEntity<PackagingEligibilityResponse> beforeSubmit = restTemplate.getForEntity(
                "/api/main-versions/{versionName}/packaging-eligibility",
                PackagingEligibilityResponse.class,
                versionName);
        assertThat(beforeSubmit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(beforeSubmit.getBody().eligible()).isFalse();
        assertThat(beforeSubmit.getBody().blockingSubVersionCodes()).containsExactly("pips");

        // 5. 담당 영역 상태 변경
        ResponseEntity<Void> statusResponse = restTemplate.exchange(
                "/api/sub-versions/{id}/submit-status",
                HttpMethod.PATCH,
                new HttpEntity<>(new SubmitStatusChangeRequest(SubmitStatus.UPDATED)),
                Void.class,
                savedSubVersion.id());
        assertThat(statusResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 6. 패키징 가능 여부 — 즉시 반영되어 가능으로 전환
        ResponseEntity<PackagingEligibilityResponse> afterSubmit = restTemplate.getForEntity(
                "/api/main-versions/{versionName}/packaging-eligibility",
                PackagingEligibilityResponse.class,
                versionName);
        assertThat(afterSubmit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(afterSubmit.getBody().eligible()).isTrue();
        assertThat(afterSubmit.getBody().blockingSubVersionCodes()).isEmpty();

        // 7. 제출 후 컴포넌트만 교체 — version/note/sortOrder가 그대로라 SubVersion.update는
        //    "변경 없음"으로 보고 제출 상태를 안 건드린다. 여기서 되돌리지 않으면 제출한 적 없는
        //    컴포넌트로 패키징이 통과한다.
        SubVersionUpsertBatchRequest retagRequest = new SubVersionUpsertBatchRequest(
                List.of(new SubVersionUpsertRequest("pips", "1.0.22.0300", "변경 사항", 1, List.of("pips:1.0.23.0100"))));
        ResponseEntity<SubVersionSavedResponse[]> retagged = restTemplate.exchange(
                "/api/main-versions/{versionName}/sub-versions",
                HttpMethod.PUT,
                new HttpEntity<>(retagRequest),
                SubVersionSavedResponse[].class,
                versionName);
        assertThat(retagged.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 8. 패키징 가능 여부 — 다시 PENDING이라 불가로 돌아온다
        ResponseEntity<PackagingEligibilityResponse> afterRetag = restTemplate.getForEntity(
                "/api/main-versions/{versionName}/packaging-eligibility",
                PackagingEligibilityResponse.class,
                versionName);
        assertThat(afterRetag.getBody().eligible()).isFalse();
        assertThat(afterRetag.getBody().blockingSubVersionCodes()).containsExactly("pips");
    }

    @Test
    void 서로_다른_서브버전이_같은_image_tag를_쓰면_400으로_거부된다() {
        String versionName = "2026.09.02";
        restTemplate.postForEntity(
                "/api/main-versions", new MainVersionCreateRequest(versionName, null, null), MainVersionInfoResponse.class);

        SubVersionUpsertBatchRequest duplicateTagRequest = new SubVersionUpsertBatchRequest(List.of(
                new SubVersionUpsertRequest("cc", "v1.0.0", null, 1, List.of("shared:v1")),
                new SubVersionUpsertRequest("api", "v1.0.0", null, 2, List.of("shared:v1"))));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/main-versions/{versionName}/sub-versions",
                HttpMethod.PUT,
                new HttpEntity<>(duplicateTagRequest),
                String.class,
                versionName);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("E-0203");
    }

    @Test
    void Job이_있는_메인버전은_서브버전_수정이_409로_거부된다() {
        String versionName = "2026.09.03";
        restTemplate.postForEntity(
                "/api/main-versions", new MainVersionCreateRequest(versionName, null, null), MainVersionInfoResponse.class);
        restTemplate.exchange(
                "/api/main-versions/{versionName}/sub-versions",
                HttpMethod.PUT,
                new HttpEntity<>(new SubVersionUpsertBatchRequest(
                        List.of(new SubVersionUpsertRequest("pips", "1.0.0", null, 1, null)))),
                SubVersionSavedResponse[].class,
                versionName);

        // package-job API로도 Job을 만들 수 있지만, 이 테스트는 ManifestLockGuard
        // (구현계획서 Phase 1-2, E-0204)만 좁게 검증하려는 것이라 DB에 직접 상태를 만든다.
        jdbcTemplate.update(
                "INSERT INTO package_job (version_name, status, created_by) VALUES (?, 'PENDING', 'tester')",
                versionName);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/main-versions/{versionName}/sub-versions",
                HttpMethod.PUT,
                new HttpEntity<>(new SubVersionUpsertBatchRequest(
                        List.of(new SubVersionUpsertRequest("pips", "1.0.1", null, 1, null)))),
                String.class,
                versionName);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("E-0204");
    }
}
