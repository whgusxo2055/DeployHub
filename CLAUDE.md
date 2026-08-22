# 저장소 구조

- git 저장소 루트는 `/mnt/c/Project`가 아니라 `backend/`이다. git 명령은 `backend/`에서 실행할 것.
- **원격 저장소(`whgusxo2055/DeployHub`)는 public이다.** 사내 호스트명(`dev-ncr-sb...`)·서버 IP·계정·실명 경로·보안장비 구성이 들어간 파일은 절대 커밋하지 말 것.
- 이 파일은 git 추적 사본이다 — 저장소 밖의 `/mnt/c/Project/CLAUDE.md`가 원본이고, 거기엔 서버 IP·계정·실명 경로·보안장비 벤더명이 실값으로 들어 있다. 이 사본은 그 4종만 치환해 둔다(2026-08-21 결정, NCR 호스트명은 이미 public이라 치환 대상 아님). **여기에 실값을 되돌려 적지 말 것**, 그리고 원본을 고치면 이 파일도 같이 고칠 것(수동 동기화).
- `docker-compose.yml`/`.env`/`.env.example`도 같은 이유로 git 추적 밖이다. 이 파일들 변경은 커밋 대상이 아니다 — 필요하면 사용자에게 `git init` 여부를 먼저 물을 것.
- **`dev-ncr-sb`는 이미 public에 올라가 있다** — 테스트 주석 3곳(`PackageJobDownloadFlowIntegrationTest` 2곳, `NcrRegistryClientTest` 1곳). 새로 넣지 말 것. 지워도 히스토리에는 남으므로 처리하려면 별도 판단이 필요하다.
- 실물 검증 없이 미룬 이슈는 `backend/docs/DEFERRED.md`에 증상-원인-그때 할 일 순으로 적는다. 같은 지적이 리뷰에서 또 나오면 그 문서를 먼저 볼 것.
- 참고 문서: `/mnt/c/Project/구현계획서.md`(FN/REQ 번호·Phase·확정 사항), `backend/README.md`(요구사항·기동·`dev` 프로필), `backend/docs/OPERATIONS.md`(환경변수·외부 권한·오류코드별 대응), `backend/docs/DEFERRED.md`(알고도 미룬 것), `/mnt/c/Project/반입가이드.md`(고객사 반입 환경·검증 절차·실측 자료). FN 번호나 기동 절차를 추측하지 말고 여기서 확인할 것.
- 패키지: `common`(ErrorCode·ApiException·재시도) · `config` · `health` · `registry`(NCR) · `sharepoint`(Graph 토큰·폴더·업로드) · `job`(Job 오케스트레이션·다운로드·정리) · `version`(메인/서브버전·컴포넌트·매니페스트 잠금).

# 빌드 트러블슈팅

- `BUILD SUCCESSFUL`은 테스트가 돌았다는 뜻이 아니다 — skopeo/docker가 없으면 `Assumptions.assumeTrue`로 조용히 스킵된다. `build/test-results/test/*.xml`의 `tests`/`skipped` 속성으로 실제 실행 건수를 확인할 것. 스킵은 **Windows/IntelliJ 한정**이고 로컬 WSL은 둘 다 있어 전부 돈다 — 테스트 서버의 값어치는 스킵 해소가 아니라 실물 NCR 도달이다.
- **Docker 데몬이 꺼져 있으면** `MySqlContainerSupport`의 static 초기화가 실패해 이를 상속한 통합 테스트 4개가 `initializationError` 하나씩으로 죽는다(`NoClassDefFoundError: Could not initialize class ...Support`). 코드 문제로 오인하기 쉽고, 이때 집계가 통합 테스트 4개 클래스만큼 줄어 보인다 — 기준치보다 눈에 띄게 낮으면 Docker부터 확인할 것.
- `./gradlew test | tail -N`처럼 파이프로 넘기면 **파이프 마지막 명령의 종료 코드**가 잡혀 BUILD FAILED가 성공(exit 0)으로 보고된다. 백그라운드 실행이면 특히 눈치채기 어렵다 — 판정은 종료 코드가 아니라 항상 `build/test-results/test/*.xml` 집계로 할 것.
- 테스트의 `System.out` 출력은 gradle 콘솔에 안 나온다 — 실측용 scratch 테스트 결과는 `build/test-results/test/*.xml`의 `<system-out>`에서 꺼낼 것. `### ` 같은 표식을 붙여 `grep -oh '### [^<]*'`로 뽑으면 편하다.
- 실행/실패 건수 집계 한 줄: `for k in tests failures errors skipped; do echo "$k: $(grep -oh "$k=\"[0-9]*\"" build/test-results/test/*.xml | grep -o '[0-9]*' | paste -sd+ | bc)"; done`
- `gradlew test`가 `NoSuchFileException: build/test-results/.../in-progress-results-generic.bin`으로 죽으면 테스트 실패가 아니라 drvfs+V3 파일 잠금이다 — `rm -rf build/test-results build/reports/tests` 후 재실행하면 통과한다. 전체 스위트는 3~6분(25개 클래스/159건, 2026-08-18 기준). 부하에 따라 11분까지 늘어난 적 있다.
- `spring.http.client.read-timeout`은 JDK 클라이언트에서 **요청 전체(바디 전송 포함) 데드라인**이다 — 소켓 read timeout이 아니다(`JdkClientHttpRequest`가 `completeOnTimeout`으로 건다). 머신 부하가 높으면 `TestRestTemplate`도 이 값을 물려받아 통합 테스트가 `HttpTimeoutException: Request cancelled`로 간헐 실패한다. 로직 실패로 오인하지 말 것.
- 로컬 전체 스위트는 `timeout 600 ./gradlew test --offline`로 돌린다 — 기본 2분 제한에 걸리고, 의존성 조회가 붙으면 더 느려진다.
- 그 전체 스위트는 Bash 도구의 600초 상한에도 걸린다 — `run_in_background`로 돌리고 종료 통보를 기다릴 것. 포그라운드는 10분에 잘려 gradle이 테스트 도중 죽는다(exit 143, 리포트 XML도 안 남아 집계가 빈다).
- **원격 컴파일이 깨지면 `t.sh`가 서버에 남은 이전 `build/`를 그대로 회수해 집계가 초록으로 보인다** — BUILD FAILED인데 "실행 172건, 실패 0"이 찍힌다. 집계 전 로컬 `backend/build-remote`뿐 아니라 **서버의 `build/test-results`·`build/reports`도 지울 것**(`ssh devsrv 'rm -rf DeployHub/backend/build/test-results DeployHub/backend/build/reports'`).
- 로컬 gradle이 `Could not create service of type FileHasher ... IOException: Input/output error`로 죽으면 drvfs에서 캐시가 깨진 것이다 — `./gradlew --stop && rm -rf .gradle` 후 재실행. 위 `in-progress-results-generic.bin`과는 다른 증상이다.
- 서브에이전트를 병렬로 돌릴 때 각자 `gradlew test`를 실행하면 같은 `build/test-results`를 밟아 서로 죽인다 — 실측이 필요한 리뷰는 순차로 돌릴 것.

# 환경 참고사항

- Windows 사용자 홈 경로에 한글이 포함됨 (`C:\Users\<한글이름>(EnglishName)` 형태). WSL에서 cmd.exe/powershell.exe를 직접 호출해 이 경로를 다루면 출력이 깨지기 쉬우니, 가능하면 `/mnt/c/Users/...` 경로로 WSL bash에서 직접 접근할 것.
- `gradlew.bat`을 WSL에서 직접 검증해야 할 때의 우회(한글 JAVA_HOME, 임시 wrapper `.bat`, Docker Desktop npipe)는 `/mnt/c/Project/환경삽질.md`.
- WSL 셸의 `curl.exe`는 Windows 바이너리(`/mnt/c/WINDOWS/system32/curl.exe`, Schannel), `curl`은 `/usr/bin/curl`(OpenSSL)이다 — TLS 스택이 달라 같은 URL에서 결과가 갈린다. 네트워크 진단 전 `which curl`로 확인할 것.
- NCR(`dev-ncr-sb.kr.ncr.ntruss.com`)은 사내망에서 보안 장비(TLS 검사)에 차단된다 — 443에서 TLS 핸드셰이크에 평문 HTTP가 돌아와 JSSE/skopeo/Schannel이 실패하고, WSL `curl`(OpenSSL)만 통과해 "정상"으로 오판하기 쉽다. 실물 검증은 휴대폰 테더링으로 전환해서 할 것. Conscrypt(BoringSSL)로 provider를 바꿔도 차단되므로 클라이언트 설정으로는 우회 불가.
- NCR 실제 도달 여부는 무인증 `GET /v2/`의 응답 헤더로 1차 판별한다 — `HTTP/2 401` + `docker-distribution-api-version: registry/2.0` + `www-authenticate: Bearer realm="https://<host>/auth/token",service="ncr"`가 오면 도달이다(차단 시엔 평문 HTTP가 와서 HTTP/2 ALPN 협상 자체가 안 된다). 단 curl 성공만으로 확정하지 말고 skopeo나 JSSE로 한 번 더 확인할 것 — curl(OpenSSL)만 통과하는 게 정확히 예전 오판 패턴이다.
- NCR blob 조회(`/v2/<repo>/blobs/<digest>`)는 307로 `kr.object.ncloudstorage.com`에 리다이렉트된다 — 그 서명 URL에 `Authorization`을 함께 보내면 403이다. 리다이렉트를 자동으로 따라가지 말고 직접 처리해 인증 헤더 없이 재요청할 것.
- drvfs의 워킹트리가 CRLF로 뒤집히는 일이 있다 — 손댄 적 없는 파일 수십 개가 수정됨으로 잡히고 `git merge`가 abort된다. `git diff --ignore-cr-at-eol`이 비면 내용 변경이 0이라는 뜻이니 `git restore .`로 정리하면 된다. 재발하면 `.gitattributes`에 `* text=auto eol=lf` 한 줄이 근본 대책이다.
- WSL `sudo`는 비밀번호를 요구한다 — `apt install`(tcpdump 등)이나 `/data` 같은 root 경로 생성이 필요하면 사용자에게 `! sudo ...` 실행을 요청할 것.
- 단 `!` 프리픽스는 TTY가 없다 — 비밀번호를 **묻는** 명령(`ssh-copy-id`, 원격 `sudo`, `ssh -t`)은 `ssh_askpass: No such file or directory`로 실패한다. 대화형 인증이 필요하면 사용자에게 별도 터미널에서 실행을 요청할 것.
- skopeo는 Windows 공식 빌드가 없다(Linux 전용 도구). `PackageDownloadService`처럼 skopeo를 `ProcessBuilder`로 직접 실행하는 프로덕션 코드와, 그걸 실제 바이너리로 검증하는 테스트(`PackageJobDownloadFlowIntegrationTest` 등)는 IntelliJ/Windows JDK에서 돌리면 `@BeforeAll`/실행 코드에서 `IOException`(파일을 찾을 수 없음)으로 죽는다. WSL에 `sudo apt install -y skopeo`로 설치하고, 이 테스트들은 WSL 터미널에서 `./gradlew test --tests "*PackageJobDownloadFlowIntegrationTest"`처럼 네이티브로 돌릴 것 — 실제 배포 서버도 NCP VPC 내부 Linux라 이 경로가 맞다. WSL에 JDK가 없으면 `sudo apt install -y openjdk-17-jdk-headless`도 필요하다(Windows JDK wrapper와는 별개). skopeo/docker를 안 쓰는 나머지 테스트 클래스는 지금처럼 Windows/IntelliJ에서 그대로 돌리면 된다.
- `ssh devsrv 'bash -s' <<EOS` 안에서 `docker compose exec -T`는 **히어독의 나머지를 stdin으로 먹어** 뒤 명령이 통째로 사라진다(오류도 안 난다) — `< /dev/null`을 붙일 것.
- 서버 mysql 클라이언트 기본 문자셋이 latin1이라 `COLLATE utf8mb4_*` 비교가 `ERROR 1253`으로 죽는다 — `--default-character-set=utf8mb4`와 `_utf8mb4'...'` 리터럴을 쓸 것.
- 이 셸에서 `grep`·`bc`·`paste`가 함수/셰임으로 덮여 `claude native binary not installed`를 뱉는 일이 있다 — `/usr/bin/grep`처럼 절대 경로로 우회하고, 집계는 `bc` 대신 awk로 할 것.

# 테스트 서버 (dev-mng-img-test)

- 사내망 NCR 차단을 우회하는 실물 검증용 서버. 접속은 ssh alias `devsrv`로만 적는다 — 실제 호스트·계정은 `~/.ssh/config`에 두고 여기에는 적지 않는다(ControlMaster 켜 둠). Ubuntu 24.04 / 2 vCPU / 7.8GB / 200G(185G 여유).
- **IntelliJ 원격개발용이 아니라 테스트 러너 전용**이다 — 2코어에 원격 IDE 백엔드까지 올리면 Gradle과 CPU를 다툰다. 소스 원본은 로컬 WSL 한 벌이고 서버로는 한 방향 rsync만 한다(`--delete`라 서버에서 직접 편집하면 날아간다).
- `/mnt/c/Project/t.sh` = rsync → 원격 `gradlew test` → 리포트를 `backend/build-remote/`로 회수 + 실제 실행/스킵 건수 출력. 인자는 gradlew에 그대로 전달된다.
- `t.sh`는 `/mnt/c/Project`에서 실행할 것 — `backend/`에서 부르면 `No such file or directory`다(스크립트가 루트에 있다).
- **집계 전에 `rm -rf backend/build-remote`를 먼저 할 것** — t.sh는 리포트를 덮어쓸 뿐 지우지 않아, 이전 실행의 XML이 남아 있으면 한 클래스만 돌려도 전체 건수가 잡힌다. 실제로 5건짜리 실행이 95건으로 보였다.
- 클래스 하나만 돌릴 때는 `--tests "com.deployhub.job.XxxTest"`처럼 **FQCN**으로 넘기는 게 안전하다(`*Xxx` 글롭은 로컬·원격 셸을 두 번 거친다).
- 서버 `sudo`는 비밀번호를 요구한다 — 패키지 설치나 `/data` 생성은 사용자에게 **서버 터미널에서** 실행을 요청할 것(위 `!` 프리픽스 항목 참고).
- 서버 DB가 로컬보다 뒤처져 있을 수 있다(2026-08-18 재기동 시 V2였다) — 재기동하면 밀린 마이그레이션이 한꺼번에 돈다. UNIQUE를 새로 거는 마이그레이션은 기존 데이터로 충돌 검사를 **먼저** 할 것(실패하면 Flyway가 죽어 앱이 안 뜬다).
- `t.sh`는 테스트 러너라 앱을 재배포하지 않는다. 배포는 t.sh의 rsync 2줄(`backend/` + `docker-compose.yml`, `.env`는 절대 제외) 후 `ssh devsrv 'cd DeployHub && docker compose up -d --build'`.
- 셸 작업 디렉터리가 도중에 `/mnt/c/Project`로 초기화되는 일이 있다 — gradlew는 항상 `cd /mnt/c/Project/backend &&`로 시작할 것.
- `gh` CLI가 없다(WSL·Windows 양쪽). PR은 `https://github.com/whgusxo2055/DeployHub/compare/master...<브랜치>?expand=1`로 열거나 github MCP를 쓴다 — user scope + `Authorization: <PAT>` 헤더(`Bearer` 접두사 불필요), 세션을 재시작해야 도구가 붙는다.
- **서버 앱을 다른 사람도 동시에 쓴다** — 감사 로그에 `createdBy=frontend` 호출이 계속 찍힌다. 재배포·파괴적 마이그레이션 전에 진행 중 Job(`GET /api/package-jobs`)을 확인하고 조율할 것.

# 환경변수 관리

- 변수 목록·기본값·`docker-compose.yml` 주입 방식은 `backend/docs/OPERATIONS.md` §1에 있다.
- **`.env`는 호스트마다 값이 다르다** — `WORK_DIR`/`SECRETS_DIR`/`GRAPH_REFRESH_TOKEN_FILE` 3개가 로컬 WSL과 테스트 서버에서 갈린다. 로컬 `.env`로 서버를 덮으면 앱이 없는 경로를 보고 기동에 실패한다(2026-08-13 발생). 서버 `.env`는 서버에서만 편집할 것.
- Graph refresh token은 work-dir '밖'이라 compose가 별도로 마운트해야 한다. **파일 단위로 걸면 안 된다** — 토큰 회전이 `.tmp → 원본` 원자적 rename이라 마운트 경계를 넘어 실패한다. 디렉터리째 걸고(`SECRETS_DIR`), 호스트 디렉터리는 0700 + uid 1001 소유여야 한다.

# 코드 패턴

- **주석·Swagger는 압축한다.** `@Operation`은 `summary` 1줄만 쓰고 `description`은 달지 않는다(예외 사유는 `@ApiResponse`가 이미 담는다). 주석·javadoc은 **2줄 이내**로, "왜"만 남기고 "무엇"은 코드가 말하게 한다. 3줄이 필요하면 그건 주석이 아니라 `DEFERRED.md` 항목이거나 테스트로 남길 것.
- `version_name`은 로컬 작업 디렉터리명(`Path.of(workDir, versionName, "images")`)과 SharePoint 폴더명으로 그대로 쓰인다 — 등록 정규식은 형식 취향이 아니라 경로 이탈 방어다(거기에 `sortKeyOf`가 `parseInt`로 파싱한다). 완화 금지. `image_tag` 쪽은 NCR REST 경로 주입만 막으면 되고 distribution 문법을 재현할 필요는 없다. tar 파일명은 `/`·`:`를 `_`로 치환할 뿐이라 단사가 아니다(`a/b:1` = `a_b:1`) — 그 충돌은 파일명이 아니라 확정 시점 검사(`assertTargetTagsValid`, E-0301)가 막는다.
- 테이블 기본 대조가 `utf8mb4_0900_ai_ci`라 **자바 `equals`와 DB 행 선택 기준이 다르다** — `cc`/`CC`·전각·ZWSP가 자바 검증을 통과하고도 같은 행을 잡는다. V4에서 `image_tag` 두 컬럼만 `utf8mb4_bin`으로 옮겼고 `code`·`version_name`은 여전히 ai_ci다. 저장·비교에는 경로 문자열이 아니라 DB에서 얻은 정규값을 쓸 것(`SubVersionWriter`의 `canonical`).
- 제약 위반을 `catch (DataIntegrityViolationException)`으로 잡으려면 `saveAndFlush`여야 한다 — `save`는 커밋 시점에 던져 catch 밖으로 샌다.
- `@Component` 클래스에 테스트 주입용 보조 생성자(예: `RestClient.Builder` 파라미터)를 추가하면, 실제 사용할 생성자에 `@Autowired`를 명시할 것. 생성자가 2개 이상이면 Spring이 (package-private이어도) 선택을 못 하고 "No default constructor found"로 기동이 죽는다.
- 외부 API 클라이언트를 테스트할 때는 `MockRestServiceServer.bindTo(RestClient.Builder)`를 그 보조 생성자에 주입하는 패턴을 쓴다 (`NcrRegistryClient`, `GraphTokenService` 참고).
- 외부 서비스 자격 증명은 `@Validated` + `@NotBlank`를 붙인 `@ConfigurationProperties` record로 바인딩한다 — 필수값 누락 시 별도 검증 코드 없이 기동이 자동으로 실패한다. `toString()`은 재정의해 시크릿 필드를 마스킹한다 (`NcrProperties`, `GraphProperties` 참고).
- `@ConfigurationProperties` record는 `DeployHubApplication`의 `@ConfigurationPropertiesScan`이 있어야 빈으로 등록된다 — 빠지면 그 record를 주입받는 컴포넌트가 기동 시 `NoSuchBeanDefinitionException`으로 즉시 실패한다.
- MySQL이 필요한(H2로 대체 불가한 스키마 기능을 쓰는) `@SpringBootTest` 통합 테스트는 `MySqlContainerSupport`(`src/test/.../support`)를 상속한다 — `@Testcontainers`/`@Container` 대신 static 초기화 블록에서 컨테이너를 한 번만 띄우는 싱글턴 패턴이다. 클래스마다 `@Testcontainers`를 쓰면 afterAll에서 컨테이너가 죽는데 Spring 컨텍스트 캐시는 JVM 종료까지 살아남아, 두 번째 통합 테스트 클래스가 죽은 커넥션을 재사용하려다 실패한다. `dev` 프로필(더미 NCR/Graph 자격증명 + StartupChecks 끔)을 함께 활성화해 외부 연동 없이 전체 컨텍스트를 띄운다.
- JPA 비관적 락(`@Lock(PESSIMISTIC_WRITE)`)을 걸기 전에 같은 엔티티를 `findById` 등으로 먼저 조회하지 말 것 — Hibernate가 락 획득 후에도 1차 캐시의 stale 인스턴스를 그대로 반환해 락이 사실상 무력화된다. 존재 여부만 필요하면 `existsById`를 쓴다 (`PackageJobService.resolveJob` 참고).
- `Executor`/`ThreadPoolTaskExecutor` `@Bean`을 하나라도 정의하면 Spring Boot의 기본 `applicationTaskExecutor` 자동 구성이 꺼진다(`@ConditionalOnMissingBean(Executor.class)`) — 이후 한정자 없는 `@Async`가 전부 그 전용 풀을 나눠 쓰게 된다. 새 `@Async`를 추가할 때는 반드시 실행기를 명시할 것 (`AsyncConfig`/`JobOrchestrator` 참고).
- MySQL `DATETIME` 컬럼(타임존 정보 없음)을 테스트에서 검증할 때, 원시 JDBC `Timestamp` 읽기와 Hibernate `Instant` 매핑은 변환 경로가 달라 값이 갈릴 수 있다 — 같은 경로(둘 다 API 응답, 또는 둘 다 JDBC)로 읽은 값끼리만 비교할 것.
- 매니페스트 `Accept`에는 단일 매니페스트 2종(docker v2 schema2, oci image manifest)에 더해 **인덱스 2종(oci image index, docker manifest list)까지** 넣을 것 — 빠지면 레지스트리가 사유를 명시한 404를 준다("OCI index found, but accept header does not support OCI indexes"). `getManifest`는 404를 `Optional.empty()`로 처리하므로 있는 이미지가 조용히 "없음"이 된다(E-0501 오탐). dev-ncr-sb 실측 기준 저장소 12개 중 3개가 인덱스였다 (`NcrRegistryClient.MANIFEST_ACCEPT` 참고).
- 반입용 아카이브는 **순수 `oci-archive:`**다 — `skopeo copy --preserve-digests --multi-arch all`. 압축이 유지돼 산출물이 레지스트리 원본 크기와 같고(`docker-archive:`는 1.76~3.37배로 불어남), 아카이브 digest가 레지스트리 digest와 **정확히 일치**한다. 구버전 Docker 미지원 결정(2026-08-11)에 따라 레거시 `manifest.json` 덧붙이기(하이브리드)는 제거했다.
- **포맷을 강제하지 말 것** — `--format v2s2`를 고정하면 인덱스에 붙은 buildx 어테스테이션에서 `Unknown media type ... vnd.in-toto+json`으로 죽는다(NCR 12개 중 3개가 해당). `--preserve-digests`는 포맷을 강제하는 대신 원본 형식을 유지해 schema2·OCI 인덱스 양쪽을 다 만족시킨다.
- **`--multi-arch all`이 필수다** — 빠지면 skopeo가 인덱스를 플랫폼 하나로 평탄화해 담아 아카이브 digest가 인덱스 digest와 달라진다(무결성 대조가 항상 오탐). 붙이면 인덱스가 통째로 보존된다(`cids` 4.4G 실측 일치).
- skopeo 목적지 참조는 `oci-archive:<경로>:docker.io/<저장소>:<태그>`처럼 **레지스트리 호스트가 붙은 완전 수식 참조**여야 한다. 이 값이 `index.json`의 `org.opencontainers.image.ref.name`이 되고 containerd 저장소가 그 문자열을 이미지 이름으로 그대로 기록하는데, 호스트가 빠지면(`acme/x:1.0`) Docker는 조회 시 `docker.io/`를 붙여 정규화하므로 기록된 이름과 영영 안 맞는다 — **적재는 성공하는데** `docker run acme/x:1.0`이 Docker Hub에서 받으려 하고(`pull access denied`), inspect/tag/rmi가 전부 `No such image`가 되며 `docker images`에 같은 행이 **두 번** 뜬다(디스크는 1배. `docker system df`의 TOTAL은 정상 계수). 태그만 넘기면 `v1.0.0:latest`로 적재되는 것도 같은 계열이다.
- 네임스페이스 없는 저장소명(`cids`·`ocr`·`piids`·`pips` 4개)은 `docker.io/library/<이름>`으로 적을 것 — Docker Hub 정규형이 그것이라, `docker.io/cids`처럼 적으면 조회 시 `docker.io/library/cids`로 정규화돼 **호스트를 아예 안 붙였을 때와 똑같은 증상**이 난다(행 2개 + 이름으로 사용 불가). `acme/<name>` 8개는 그대로 `docker.io/acme/<name>`이면 된다. 표시 이름에는 영향 없다(Docker가 `docker.io/library/`를 표시에서 뗀다). **schema2 단일 매니페스트로만 검증하면 이 버그를 놓친다** — NCR에서 네임스페이스 없는 4개 중 3개가 인덱스라 두 조건이 겹쳐 보이지만, 원인은 인덱스가 아니라 네임스페이스 유무다(7MB 이미지로 분리 확인).
- 그 호스트에 **실 NCR 엔드포인트를 쓰면 안 된다** — 아카이브는 SharePoint를 거쳐 고객사로 나가므로 사내 레지스트리 주소가 실린다. `docker.io`를 명시하면 정규화 결과와 같아져 이름이 살아나고, `docker images`가 `docker.io/` 접두사를 표시에서 떼므로 고객사가 보는 이름은 `acme/x:1.0` 그대로다(실측).
- 고객사 반입 환경(containerd 저장소 하한, 저장소 방식 전환 증상, 이름 정규화로 생기는 오적재)과 skopeo/`docker pull` 선택 근거는 `/mnt/c/Project/반입가이드.md`에 있다 — 반입 판단은 그 문서를 먼저 볼 것.
- 외부 프로세스 출력을 `getInputStream().readAllBytes()`로 읽은 뒤 `waitFor(timeout)`을 부르면 타임아웃이 무력화된다 — EOF는 프로세스가 끝나야 오므로 읽기에서 무한정 막히고, 뒤의 `waitFor`는 이미 끝난 프로세스를 확인할 뿐이다. 별도 리더 스레드로 비우면서 `waitFor`할 것 (`runSkopeo` 참고).
- 인덱스 응답에는 `layers`가 없어 크기 합계가 0이 된다 — 자식 매니페스트를 **전부** 조회해 합산할 것. `--multi-arch all`로 받으므로 buildx 어테스테이션(`platform`이 unknown/unknown)도 아카이브에 담기니 빼면 안 된다. 반환 digest는 태그가 가리키는 인덱스 digest를 그대로 둔다(skopeo 비교 대상과 같아야 함).
- 무결성은 **서로 다른 두 질문**이라 검사도 둘이다. ①"받는 도중 원본이 바뀌었나"(같은 태그 재푸시) → 다운로드 전/후 모두 REST로 매니페스트를 재조회해 비교(E-0603). ②"아카이브가 충실한 복사본인가" → skopeo가 copy 중 blob마다 digest를 검증하고 `--preserve-digests`가 보존 실패 시 0이 아닌 코드로 끝내므로 이미 담보된다. 아카이브를 다시 열어 대조하지 말 것 — 파일 전체를 훑게 되고, ②는 이미 커버된다.
- 아카이브 목적지는 기존 파일 수정을 지원하지 않는다("does not support modifying existing images") — 재시도 로직에서 목적지 tar는 마지막 실패 시점이 아니라 매 시도 시작 시 삭제할 것.
- skopeo가 쓰는 항목 순서는 `blobs → 매니페스트 → index.json → oci-layout`이라 `index.json`이 끝에서 두 번째다 — `tar -O --occurrence=1`로 읽어도 사실상 끝까지 훑는다. 후처리를 제거한 이유 중 하나다(실측: `index.json` 읽기 100% + 매니페스트 blob 읽기 53% = 아카이브의 153%. 매니페스트 blob 위치는 digest 정렬 순서에 따라 0~100%로 가변이라 최악 2배).
- 배치 단위 병렬 처리(`CompletableFuture` 등)에서 하나가 실패해도 나머지를 전부 `join()`해서 끝낸 뒤 예외를 던질 것 — 아니면 형제 프로세스(skopeo 등)가 고아로 남는다 (`BoundedParallelism` 참고).
- 외부 CLI 인증은 `--src-creds` 같은 명령행 인자 대신 `REGISTRY_AUTH_FILE`(임시 0600 JSON) 방식을 쓴다 — CLI 인자는 `ps`/`/proc/<pid>/cmdline`으로 노출된다.
- 상태 전이 API(재시도 등)는 조회 시 `findById`가 아니라 락 걸린 리포지토리 메서드(`findByVersionName` 등)를 쓸 것 — 아니면 동시 요청이 상태 체크를 둘 다 통과해 중복 실행된다.
- `ObjectMapper`는 주입받는 게 기본이다(2026-08-20 결정). `new ObjectMapper()`는 `FAIL_ON_UNKNOWN_PROPERTIES=true`, Boot 빈은 `false`라 주입하면 엄격도가 낮아진다 — 그래서 바인딩 대상 record에는 `@JsonIgnoreProperties`를 **명시**한다(`NcrRegistryClient.TokenResponse`). `NcrRegistryClientTest`가 엄격한 기본 매퍼를 넘기는 건 그 애노테이션이 사라지는 걸 잡는 유일한 가드라 일부러 프로덕션과 다르게 둔 것이다. `PackageDownloadService`의 매퍼는 skopeo 인증 파일 직렬화 전용이라 엄격도와 무관했다(`FAIL_ON_UNKNOWN_PROPERTIES`는 역직렬화 기능이다) — 두 클래스를 한 근거로 묶었던 옛 서술이 틀렸던 것이다. 이제 프로덕션에 `new ObjectMapper()`는 없다.
- `PackageJobRepository.getOrThrow`(락 없음)와 `lockOrThrow`(`FOR UPDATE`)를 **하나로 합치지 말 것** — 상태를 보고 갱신하는 경로(`retry`, `purge`)는 반드시 락이 걸린 쪽이어야 하는데, 합치면 다음 사람이 락 없는 쪽을 고른다. `resolveJob`은 없을 때 `JOB_CREATION_CONFLICT`로 던져야 해서 둘 다 안 쓴다.
- `package_item.error_message`는 무인증 `GET /api/package-jobs/{versionName}` 응답에 **그대로 실린다** — 저장 전에 반드시 정제할 것(서버 경로·호스트·업스트림 응답 본문 금지). 원문은 `PackageItemFailure.fail`의 `detail`로 넘겨 로그에만 남긴다.
- `RestClientResponseException.getMessage()`는 `RestClient`에서 `400 Bad Request: "<응답 본문>"` 형태다 — **URL은 안 들어가지만 업스트림 응답 본문은 통째로 들어간다**. URL까지 붙는 건 `RestTemplate` 경로라 이 코드베이스엔 해당 없다(실측 확인).
- 서비스·클라이언트 계층 스테레오타입은 `@Service`로 통일했다. `@Component`는 `ApplicationRunner`(`StartupChecks`, `OrphanJobCleaner`)와 설정 보조(`UploadChunkSizeValidator`)에만 쓴다.
- skopeo/docker 등 실제 바이너리가 필요한 통합 테스트는 mock 대신 Testcontainers `registry:2` + 실제 CLI로 검증한다 — `Assumptions.assumeTrue`로 CLI 없는 환경(Windows/IntelliJ)에서는 하드 실패 대신 스킵되게 한다 (`PackageJobDownloadFlowIntegrationTest` 참고).
- **오류 코드는 `ErrorCode` enum 하나에만 정의한다** — 메서드 본문에서 `"E-1102: ..."`처럼 문자열로 만들지 말 것(2026-08-20 통합). 예전에는 `ErrorCode`/`ItemErrorCode` 둘뿐이라 로그 전용 코드가 갈 곳이 없어 11개가 메서드 본문에 흩어져 있었다.
- **노출 경계는 `ErrorCode.Exposure`가 정한다.** `PUBLIC`은 HTTP 응답이나 무인증 `GET /api/package-jobs/{versionName}`의 `error_message`로 나가므로 **문구에 서버 경로·호스트를 넣지 말 것**. `LOG`는 로그에만 남아 경로를 실어도 된다. 강제는 세 겹이다 — ①`PackageItem.markFailed(ErrorCode)`가 문자열을 안 받는다 ②`ApiException`이 `LOG` 코드를 거부한다 ③`ErrorCodeExposureTest`가 `PUBLIC` 문구에 경로 패턴이 없는지, `HttpStatus`가 있는 코드가 `PUBLIC`인지 검사한다. 컨텍스트는 `toLogMessage(detail)`로만 붙이고, `ApiException`의 `details`는 값이 들어가는 자리라 검사 대상이 아니다(리뷰로 본다).
- 컨트롤러의 `@ApiResponse(description = "E-xxxx: ...")`만 예외다 — 정의가 아니라 Swagger 문서라 유지하되, enum 문구와 따로 관리되므로 드리프트에 주의할 것.
- **HTTP 응답 메시지는 `ErrorCode` enum에만 둔다** — `ApiException`에 문자열을 넘기는 생성자는 없앴다. 컨텍스트는 `details`(키=값 형태)로 넘기고, 문구가 달라야 하는 상황이면 코드를 새로 정의할 것(예: "진행 중" E-1404 vs "그 사이 재실행됨" E-1405).
- 항목 실패 사유(`package_item.error_message`)도 같은 규칙으로 `ItemErrorCode` enum이다. 이 값이 무인증 응답에 그대로 실리므로 서버 경로·업스트림 본문은 `PackageItemFailure.fail`의 `detail`로 넘겨 로그에만 남긴다.
- 외부 HTTP는 트랜잭션 밖에서 부른다. **같은 빈 안에서 메서드를 나눠 불러도 프록시를 안 타므로** 쓰기 구간을 별도 빈으로 뺄 것 (`SubVersionService` → `SubVersionWriter`).
- "컴포넌트 수정"과 "매니페스트 확정"은 `MainVersionRepository.lockByVersionName`(같은 `main_version` 행)으로 직렬화한다 — 두 경로 중 하나라도 락을 안 잡으면 확정된 매니페스트와 DB가 어긋난 채 패키징이 돈다.
- 메인버전 정렬과 "직전 버전" 판정은 `version_name`이 아니라 `sort_key`로 한다 — 문자열 비교는 index가 두 자리가 되는 순간 뒤집힌다(`'2026.08.05-10' < '2026.08.05-2'`).
- `@ConfigurationProperties`의 `Duration`에는 `@DurationUnit`을 붙일 것 — 없으면 단위 없는 값(`5,15`)이 **밀리초**로 바인딩돼 재시도 백오프가 조용히 꺼진다. 로그가 `toSeconds()`면 항상 0초로 찍혀 눈치채기 어렵다.
- `HttpHeaders.getValuesAsList`는 따옴표를 무시하고 콤마로 쪼갠다 — `WWW-Authenticate: Bearer realm="...",service="..."`가 두 조각으로 망가진다. 원본 헤더 줄이 필요하면 `get()`을 쓸 것.
- `@Value`에 `Duration` 파라미터를 쓰지 말 것 — `@ConfigurationProperties`와 달리 변환기가 없어 "no matching editors"로 기동이 죽는다. 문자열로 받아 `DurationStyle.detectAndParse(값, ChronoUnit.SECONDS)`로 파싱한다 (`GraphApiClient` 참고).
- RestClient에 타임아웃을 따로 주려고 `ClientHttpRequestFactorySettings.defaults()`를 새로 만들지 말 것 — 전 필드가 null이고 Boot의 PropertyMapper가 null은 안 걸어서 전역 `connect-timeout`과 리다이렉트 정책이 통째로 사라진다(JDK 기본값=무제한). 자동 구성된 `ClientHttpRequestFactoryBuilder`/`ClientHttpRequestFactorySettings` 빈을 주입받아 필요한 값만 덮어쓸 것.
- `BeanWiringSmokeTest`는 자동 구성 없는 베어 `AnnotationConfigApplicationContext`다 — 컴포넌트가 자동 구성 빈을 새로 주입받으면 그 빈을 `TestConfig`에 수동 등록해야 한다(실제 Boot 컨텍스트 확인은 `MainVersionApiFlowIntegrationTest`가 한다).
- `PackageJobApiFlowIntegrationTest`의 `@AfterEach`가 **삭제 재시도**인 이유 — 비동기 Job이 방금 지운 `package_item`을 detached merge로 되살려 FK 위반이 난다(전체 스위트 부하에서만 재현). Job 상태가 종료될 때까지 기다리는 방식으로 바꾸지 말 것: 진행 중 상태를 JDBC로 직접 심고 두는 테스트가 있어 영원히 안 끝난다.
- `UploadChunkSizeValidator`를 `GraphUploadService` 생성자로 합치지 말 것 — 업로드 테스트 8건이 10바이트 청크로 서비스를 만들어 분할 전송을 검증한다(합쳤다가 전부 기동에서 죽어 되돌림).
- **Graph SDK(`com.microsoft.graph`) 도입은 보류다(2026-08-20 측정 후 결정).** 의존성 실해석 결과 jar +34개·+63.5MiB(런타임 55→119MiB), OkHttp+Kotlin·azure-core+reactor가 들어와 HTTP 스택 2개·JSON 라이브러리 3개가 공존한다. 정작 인증(device code로 받은 refresh token을 파일에 보관·회전)은 SDK가 대체하지 못해 217줄이 그대로 남는다. NCR용 자바 SDK는 존재하지 않는다(Maven Central `ncloud` 0건).

# 리뷰 규칙

- 코드 변경 후에는 `everything-claude-code:code-reviewer`를 돌린다.
- Hibernate 6(Boot 3.5.x) HQL은 `LIMIT`을 지원한다 — `@Query`의 `LIMIT 1`을 "JPQL 문법 오류"로 지적하는 리뷰는 오탐이다(`MainVersionRepository.findPrevious`, 통합 테스트로 실행까지 확인).
- 시크릿·인증·API 엔드포인트를 다루면 `security-review` 스킬(또는 `everything-claude-code:security-reviewer`)도 돌린다.
- 서브에이전트 리뷰 결과는 **사실 주장을 실측한 뒤** 반영한다 — 특히 "프레임워크가 X를 한다"류는 임시 테스트로 재현해 볼 것(검증용 테스트는 `src/test/.../scratch/`에 만들고 확인 후 삭제, 검증용 `.bat`과 같은 취급). 실제로 이번 보안 리뷰의 MEDIUM 1건이 오탐이었다.
- 동작을 바꾸는 수정에 회귀 테스트를 붙일 땐 **수정 전 코드에서 그 테스트가 실제로 실패하는지 먼저 확인**한다 — 통과만 확인하면 아무것도 안 잡는 가짜 안전망이 남는다.
- 그 확인에서 **테스트 픽스처가 실제 구성과 다르면 단언이 헛돈다**. 실례: digest 보존 회귀 테스트를 붙였는데 로컬 `registry:2` 시딩이 alpine(OCI 형식)을 그대로 밀어 넣어 변환이 애초에 없었고, `--preserve-digests`를 빼도 통과했다. 시딩을 `--format v2s2`로 바꾸니 그제서야 실패했다 — dev-ncr-sb도 12개 중 9개가 schema2라 이쪽이 실제 구성이다.
- 코드베이스 전 범위 리뷰는 모듈별(`registry`/`sharepoint`/`job`/`version`/공통기반)로 나눠 병렬로 돌린다 — 한 번에 시키면 훑고 지나간다. 각 프롬프트에 그 모듈의 실측 사실(이 파일의 항목들)을 함께 넣을 것.
- "수정 전 실패 확인"은 `git stash` 말고 손으로 되돌릴 것 — 워킹트리에 커밋 안 된 작업이 쌓여 있으면 `git stash push -- <파일>`이 그 파일의 **다른 변경까지** 전부 HEAD로 되돌린다.
- 서브에이전트에 주입되는 CLAUDE.md는 **스폰 시점 사본**으로 보인다 — 세션 중 고친 내용을 근거로 든 지적은 최신본과 대조할 것(실제로 "CLAUDE.md에 X라고 적혀 있다"가 이미 고쳐진 내용이었다).

# 실물 검증 규칙

- 실제 NCR로 검증할 때는 **pull만** 한다 — 토큰 scope는 `repository:<repo>:pull`/`registry:catalog:*`만, skopeo 목적지는 로컬 아카이브 tar로만 잡는다. 레지스트리 push 금지.
- NCR 수동 조회는 Bearer 토큰 흐름을 타야 한다 — `/v2/_catalog`에 Basic만 보내면 `UNAUTHORIZED`다. 토큰 realm은 `/v2/token`이 아니라 `https://<host>/auth/token?service=ncr&scope=...`이다(`/v2/token`은 405). `/v2/`에 무인증 요청해 `WWW-Authenticate` 헤더로 확인할 수 있다.
- `dev-ncr-sb` 저장소 이름에는 네임스페이스가 붙는 것과 안 붙는 것이 섞여 있다 — 8개는 `acme/<name>`, 4개(`cids`·`ocr`·`piids`·`pips`)는 접두사가 없다. 이름을 추측하지 말고 `_catalog`로 먼저 확인할 것(틀리면 `repository name not known to registry`).
- 수동 검증용 자격증명은 루트 `.env`에서 읽고 argv에 넣지 말 것 — curl은 `-K <0600 설정파일>`, skopeo는 `REGISTRY_AUTH_FILE`을 쓰고 끝나면 삭제한다(`ps`/`/proc/<pid>/cmdline` 노출 방지).
- NCR은 **없는 저장소·없는 태그 모두 404**를 준다(401 아님) — 등록 시점 존재 검증(E-0206)이 성립하는 근거다. 2026-08-13 실측.
- 반입 검증 절차(dind 버전별 확인, 벤치마크 함정, gzip ISIZE 측정, 최소 비용 검증 이미지)는 `/mnt/c/Project/반입가이드.md`.
- distribution 규격상 **저장소명은 소문자 강제**다(`ACME/cc-sb` 푸시 시 `repository name must be lowercase`) — 대소문자 차이는 **태그에서만** 가능하다. dev-ncr-sb 실측(2026-08-20): 태그 1217건 중 대문자 포함 13건(전부 `-SNAPSHOT`), 소문자로 접었을 때 충돌 쌍 0건.

# 커밋 규칙

- **커밋 시점**: 사용자가 명시적으로 커밋을 요청할 때만 커밋한다. 작업 단위가 끝났다고 임의로 커밋하지 않는다.
- **메시지 형식**: [Conventional Commits](https://www.conventionalcommits.org/)를 따른다. 본문에는 "무엇"이 아니라 "왜"를 적는다(무엇은 diff가 말한다).
- **본문 마지막 줄**: `Co-Authored-By: Claude <모델명> <noreply@anthropic.com>` — 실제 작업한 모델명을 쓴다(예: `Claude Opus 5`). 고정 문자열이 아니다.
- 시크릿/자격증명이 포함된 파일(.env, credentials 등)은 커밋하지 않는다. `git add -A`/`git add .` 대신 파일명을 명시해서 스테이징한다.
- `--amend`, `--force`, `--no-verify` 등은 사용자가 명시적으로 요청하지 않는 한 사용하지 않는다.
- 이미 만든 로컬 커밋의 메시지를 다시 쓰고 싶을 때: `git log --oneline -1 origin/<branch>`로 아직 푸시되지 않았는지 먼저 확인한 뒤, `git reset --soft <직전 커밋>`으로 되돌리고 다시 커밋한다 (`rebase -i`는 `-i` 플래그 금지 규칙에 걸리므로 대신 이 방법을 쓴다).
- 머지된 브랜치를 지울 때 로컬이 자기 원격 추적 브랜치보다 앞서 있으면 `git branch -d`가 거부한다(HEAD에는 머지됐어도). **원격을 먼저 지우고 로컬을 지울 것** — 커밋이 이미 master에 있고 푸시까지 끝났다면 잃는 건 없다.
