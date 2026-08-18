# DeployHub — 배포패키지 자동화 백엔드

폐쇄망 고객사 반입을 위해, NCP Container Registry(NCR)의 컨테이너 이미지를 메인버전 단위로
모아 반입용 아카이브(`.tar`)로 만들고 SharePoint에 올려 전달 링크를 내주는 서비스다.
백엔드 API + Swagger 명세까지가 범위이며 화면은 별도 주체가 담당한다.

전체 설계·Phase 구성은 저장소 밖 `구현계획서.md`를 따른다.

## 요구사항

| | 버전 | 비고 |
|---|---|---|
| JDK | 17 | Gradle toolchain이 강제 |
| MySQL | 8.0 | Flyway가 스키마를 관리 |
| skopeo | Linux 전용 | **런타임 하드 의존.** 없으면 기동이 E-0605로 실패한다 |

> skopeo는 Windows 공식 빌드가 없다. 개발 중이라도 실제 다운로드 경로를 돌리려면
> WSL이나 리눅스에서 실행할 것. 운영은 NCP VPC 내부 리눅스가 전제다.

## 기동

`.env.example`을 저장소 루트(`backend/`의 상위)에 `.env`로 복사해 값을 채운 뒤:

```bash
docker compose up -d --build      # app(8080) + db(3306)
curl localhost:8080/api/health/registry
```

개발 중에는 DB만 띄우고 앱은 IDE에서 실행한다 — `docker-compose.yml`로 app까지 띄우면
IDE의 `bootRun`과 8080이 충돌한다.

```bash
docker compose -f docker-compose.dev.yml up -d
```

### 프로필

기본 프로필은 실 자격 증명을 요구한다. `dev` 프로필은 NCR/Graph 자격 증명에 placeholder를
채우고 기동 점검(`StartupChecks`)을 꺼서, 키 없이도 컨텍스트를 띄운다.

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'   # Swagger 명세 확인용
```

`dev`로 뜬 인스턴스는 실제 패키징과 `/api/health/*`가 전부 실패한다. 운영 배포에 쓰지 말 것.
`application-dev.yml`은 `${NCR_ENDPOINT:placeholder}` 패턴이라, 환경변수를 채우면 같은
프로필로도 실 연동이 된다.

## API

`SWAGGER_ENABLED=true`일 때 `/swagger-ui.html`, 원본 명세는 `/v3/api-docs`.
로그인 기능이 없으므로 운영에서는 비활성화하거나 nginx에서 내부 IP만 허용한다.

| 메서드 · 경로 | 기능 |
|---|---|
| `GET /api/main-versions` | 메인버전 목록 (FN-01) |
| `GET /api/main-versions/{v}` | 메인버전 + 서브버전 조회 (FN-02) |
| `POST /api/main-versions` · `PUT /api/main-versions/{v}` | 메인버전 등록·수정 |
| `PUT /api/main-versions/{v}/sub-versions` | 서브버전 일괄 등록·수정 (FN-02-1) |
| `DELETE /api/sub-versions/{id}` | 서브버전 삭제 |
| `GET /api/main-versions/{v}/packaging-eligibility` | 패키징 가능 여부 (FN-02-1) |
| `GET /api/main-versions/{v}/changed-components` | 직전 대비 변경 컴포넌트 (FN-03) |
| `POST /api/main-versions/{v}/package-job` | 매니페스트 확정·Job 생성 (FN-03) |
| `GET /api/package-jobs` · `GET /api/package-jobs/{v}` | Job 목록·상세 (진행률 폴링) |
| `POST /api/package-jobs/{v}/retry` | 실패 항목 재시도 (FN-07) |
| `GET /api/package-jobs/{v}/files` | 폴더 공유 링크 + 파일별 URL (FN-10) |
| `DELETE /api/package-jobs/{v}/package` | 패키지 수동 정리 (FN-11) |
| `POST /api/admin/cleanup` | 보존·정리 배치 수동 실행 (FN-11) |
| `GET /api/health/registry` · `/api/health/sharepoint` | 외부 연동 점검 (FN-04-1·FN-04-5) |

운영 절차·환경변수·오류 코드별 대응은 [docs/OPERATIONS.md](docs/OPERATIONS.md)를 참고한다.
알고도 미뤄 둔 이슈와 그때 할 일은 [docs/DEFERRED.md](docs/DEFERRED.md)에 있다.

## 테스트

```bash
./gradlew test        # 16개 클래스 / 약 5분
```

`BUILD SUCCESSFUL`은 테스트가 돌았다는 뜻이 아니다 — skopeo/docker가 없으면
`Assumptions.assumeTrue`로 조용히 건너뛴다. 실제 실행 건수는
`build/test-results/test/*.xml`의 `tests`/`skipped` 속성으로 확인할 것.

실제 NCR을 대상으로 다운로드 경로를 검증하려면 존재하는 태그를 지정한다 (pull만 한다):

```bash
NCR_TEST_IMAGE_TAG=acme/swg-tls-proxy:unspecified.7646 \
  ./gradlew test --rerun --tests "*PackageJobDownloadFlowIntegrationTest"
```

`--rerun`이 필요하다 — 환경변수는 Gradle 태스크 입력이 아니라서, 빼면 태그만 바꿔도
직전 결과를 `UP-TO-DATE`로 재사용한다.
