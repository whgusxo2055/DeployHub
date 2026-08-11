# DeployHub 운영 가이드

기동 절차와 API 목록은 [README](../README.md)에 있다. 이 문서는 운영 중에 필요한 것만 다룬다.

## 1. 환경변수

전체 목록과 기본값은 저장소 루트 상위의 `.env.example` 하나만 유지한다 — `backend/` 아래에
따로 두지 않는다(drift 발생). `docker-compose.yml`의 `app`은 `env_file: .env`로 전체를 주입하고
컨테이너 네트워크용 `DB_HOST: db`만 덮어쓴다. **새 변수는 `.env.example`에만 추가하면 된다.**

기동을 좌우하는 것들만 짚는다.

| 변수 | 기본 | 주의 |
|---|---|---|
| `WORK_DIR` | `/data/deployhub/jobs` | 아카이브가 쌓이는 곳. 디스크 여유의 기준이다 |
| `NCR_ENDPOINT`·`NCR_ACCESS_KEY`·`NCR_SECRET_KEY` | — | 비어 있으면 **기동 실패**(`@NotBlank`) |
| `GRAPH_TENANT_ID`·`GRAPH_CLIENT_ID`·`GRAPH_CLIENT_SECRET`·`SP_SITE_ID` | — | 동일 |
| `NCR_CLI_PATH` | `/usr/bin/skopeo` | 실행 불가면 기동 실패(E-0605) |
| `STARTUP_CHECKS_ENABLED` | `true` | 끄면 NCR 도달성·skopeo·tar 점검을 건너뛴다. 운영에서 끄지 말 것 |
| `SWAGGER_ENABLED` | `true` | 운영에서는 끄거나 nginx로 내부 IP만 허용 |
| `CORS_ALLOWED_ORIGINS` | — | 프론트엔드 **브라우저 주소창**의 오리진이다(이 백엔드 IP가 아니다). 스킴 필수, 포트 와일드카드는 `:[*]` |
| `UPLOAD_CHUNK_SIZE` | `10485760` | 320 KiB의 양의 배수 + 60 MiB 이하만 허용. 아니면 기동 실패(E-1108) |
| `JOB_CONCURRENCY` | `3` | 동시 Job 수. skopeo 프로세스는 최대 `JOB_CONCURRENCY × DOWNLOAD_CONCURRENCY`개까지 뜬다 |
| `MIN_FREE_DISK_BYTES` | `53687091200` (50GB) | 확정 시점 여유 공간 경고 기준 |
| `RETENTION_DAYS` | `90` | SharePoint 폴더 보존 기간. **1 미만이면 기동 실패** |
| `RETENTION_COUNT` | `10` | 기한이 지나도 보호할 최근 건수. 음수면 기동 실패 |
| `LOCAL_CLEANUP_DELAY_HOURS` | `24` | 업로드 완료 후 작업 디렉터리 삭제 유예 |

> 보존 정책 3개는 기동 단계에서 값을 검증한다. `RETENTION_DAYS=-90` 같은 오타 하나가
> "기한 경과" 판정을 전건 통과시켜 복구 불가능한 전량 삭제로 이어지기 때문이다.

## 2. 외부 권한 요건

**NCP Container Registry** — pull 전용이면 충분하다. 토큰 scope는
`repository:<repo>:pull`과 `registry:catalog:*`만 쓰고, push는 하지 않는다.

**Microsoft Graph** — `Sites.ReadWrite.All`(애플리케이션 권한, 관리자 동의 필요).
`Files.ReadWrite.All`은 앱 전용 인증에서 `createUploadSession`을 지원하지 않으므로 쓸 수 없다.

권한과 **별개로** 테넌트가 `organization` 범위 공유 링크를 막고 있으면 링크 발급이 실패한다.
그때는 폴더 `webUrl`로 폴백하고 Job은 완료시키되 경고(E-1005)로 남는다. 권한 신청 시
공유 링크 정책도 함께 확인할 것.

## 3. 정리 배치 (FN-11)

일 1회 **KST 03:00**에 돈다(`deployhub.retention.cron`, zone `Asia/Seoul` 고정).
두 단계의 기한이 다르다.

| 단계 | 대상 | 기한 | 지우는 것 |
|---|---|---|---|
| 1 | `DONE` Job | `finished_at` + `LOCAL_CLEANUP_DELAY_HOURS` | 작업 디렉터리만 |
| 2 | `DONE`·`FAILED` Job 중 `deleted_at IS NULL` | `finished_at` + `RETENTION_DAYS` | SharePoint 폴더 + 작업 디렉터리, `deleted_at` 기록 |

2단계는 **최근 `RETENTION_COUNT`건을 기한과 무관하게 보호한다.** 1단계에는 보호 규칙이 없다 —
업로드가 끝났으면 디스크는 회수해도 되기 때문이다.

`package_job`/`package_item` **행 자체는 지우지 않는다.** 정리 후에도 이력 조회가 된다.

### 수동 실행

```bash
# 대상만 산출 (기본값이 dry run이다)
curl -X POST 'localhost:8080/api/admin/cleanup'

# 실제 삭제 — dryRun=false를 명시해야 한다
curl -X POST 'localhost:8080/api/admin/cleanup?dryRun=false'

# 특정 메인버전만 즉시 정리 (보호 규칙과 무관)
curl -X DELETE 'localhost:8080/api/package-jobs/2026.08.05/package'
```

`dryRun` 기본값이 `true`인 것은 의도다 — 인증이 없어 파라미터 없는 POST 한 방이 곧 실삭제가
되면 안 된다. Swagger UI의 "Try it out" 기본 상태가 그 경로다.

**응답 읽는 법** — `sharePointCleaned`는 *실제로 폴더를 지운* 건만 담는다. 폴더가 없던 Job은
2단계를 처리해도 여기 오르지 않으므로, 처리 여부는 `deleted_at`으로 확인한다.
`failed`에 오른 건은 다음 배치가 자동으로 재시도한다.

## 4. 오류 코드별 대응

### 기동이 안 될 때

| 코드 | 원인 | 대응 |
|---|---|---|
| (기동 실패) | `NCR_*`/`GRAPH_*`/`SP_SITE_ID` 누락 | `@NotBlank` 검증이다. `.env` 확인 |
| E-0605 | skopeo 실행 불가 | `NCR_CLI_PATH` 확인. 컨테이너 이미지에는 포함돼 있다 |
| E-0404 | NCR에 연결 불가 | 아래 "NCR 도달성" 참고 |
| E-1108 | `UPLOAD_CHUNK_SIZE`가 320 KiB 배수가 아님 | 값 수정 |
| (기동 실패) | 보존 정책 값이 범위 밖 | `RETENTION_DAYS`≥1, `RETENTION_COUNT`≥0 |

### 운영 중

| 코드 | 의미 | 대응 |
|---|---|---|
| E-0204 | 진행 중이거나 완료된 메인버전의 매니페스트 수정 시도 | 정상 차단. `FAILED`만 수정 가능 |
| E-0302 | 이미 Job이 있음 | 재생성하려면 `force=true` |
| E-0304 | 작업 디렉터리 여유 공간 부족 | 정리 배치를 수동 실행하거나 `MIN_FREE_DISK_BYTES` 재검토 |
| E-0305 | `PENDING` 담당 영역 잔존 | 해당 영역의 Release History 제출을 기다린다 |
| E-0401 | 레지스트리 인증 실패 | 액세스키 확인. **단 사내망 차단도 이 증상으로 보일 수 있다** |
| E-0402·E-0404 | 레지스트리 타임아웃·연결 불가 | 네트워크. 아래 참고 |
| E-0452 | Graph 권한 부족 | `Sites.ReadWrite.All` 관리자 동의 여부 확인 |
| E-0453 | Graph 일시 장애 | `RETRY_BACKOFF`에 따라 자동 재시도. 지속되면 서비스 상태 확인 |
| E-0501 | 이미지 없음 | `image_tag` 오타가 가장 흔하다. 등록 시점 검증은 하지 않는 설계다 |
| E-0603 | digest 불일치 | 다운로드 도중 태그가 갱신된 경우. 재시도 |
| E-0702 | 완료/진행 중 Job 재시도 시도 | 정상 차단 |
| E-0703 | 작업 디렉터리 소실 | `force=true`로 전체 재수집 |
| E-1201 | 아직 완료 전 | 응답 `details`에 현재 상태·진행률이 들어 있다 |
| E-1404 | 진행 중 Job의 패키지 정리 시도 | 정상 차단. Job 종료 후 재시도 |
| E-1502 | 실행 대기열 포화 | `JOB_CONCURRENCY` 상한. 잠시 후 재요청 |

### NCR 도달성 판별

무인증 `GET /v2/`의 응답 헤더로 1차 판별한다.

```bash
curl -sS -o /dev/null -D - https://<host>/v2/
```

도달이면 `HTTP/2 401` + `docker-distribution-api-version: registry/2.0` +
`www-authenticate: Bearer realm="https://<host>/auth/token",service="ncr"`가 온다.

**사내망에서는 보안 장비가 443에 평문 HTTP를 돌려주므로 HTTP/2 ALPN 협상 자체가 안 된다.**
이때 TLS 스택에 따라 결과가 갈린다 — OpenSSL curl만 통과하고 JSSE/skopeo/Schannel은 실패해서
"앱만 고장난 것"으로 오판하기 쉽다. curl 성공만으로 확정하지 말고 skopeo나 앱으로 한 번 더
확인할 것. 클라이언트 설정으로는 우회할 수 없다.

## 5. 알려진 제약

- **Job 이력은 메인버전당 1건이다.** `package_job`의 PK가 `version_name`이라 `force` 재생성 시
  이전 이력이 덮어써진다. 최소한의 흔적은 `audit` 로거가 남긴다(Job 생성자·매니페스트 구성·
  강제 여부·정리 대상). 지금은 stdout으로 나가므로 **컨테이너를 재기동하면 소실된다** —
  이력 보존이 실제 요구가 되면 별도 appender와 `package_job` 대리키가 정공법이다.
- **인증이 없다.** `/api/**` 전체가 무인증이고 CORS도 열려 있다. 사내망 격리를 전제로 수용한
  리스크이므로, NCP ACG에서 소스 IP를 반드시 제한한다.
- **정리는 단일 인스턴스를 전제로 한다.** 여러 인스턴스를 띄우면 스케줄러가 같은 대상을
  중복으로 집을 수 있다(Graph 404로 흡수되어 치명적이진 않다).
- **고객사 Docker는 24 이상 + containerd 이미지 저장소여야 한다.** 산출물이 순수 OCI 레이아웃
  (`oci-archive:`)이라, 구식 classic(graph driver) 저장소는 `docker load`에서 다음과 같이 실패한다:

  ```
  invalid archive: does not contain a manifest.json
  ```

  조건은 **버전이 아니라 저장소 방식**이다(dind 실측). Docker 29는 기본으로 켜져 있지만 **끄면
  실패**하고, 24~28은 명시적으로 켜야 하며, 23 이하는 켜도 안 된다.

  확인:
  ```bash
  docker info -f '{{.DriverStatus}}' | grep -q io.containerd.snapshotter \
    && echo "OK" || echo "설정 필요"
  ```

  켜기(24~28) — `/etc/docker/daemon.json`에 아래를 넣고 `systemctl restart docker`:
  ```json
  { "features": { "containerd-snapshotter": true } }
  ```

  > 전환하면 기존 이미지가 `docker images`에서 **안 보인다.** 지워진 게 아니라 두 저장소가
  > `/var/lib/docker` 안에 공존하고 데몬이 한쪽만 보기 때문이며, 설정을 되돌리면 그대로
  > 복구된다. 다만 새 저장소는 비어 있으므로 필요한 이미지는 다시 받아야 한다.

- **zstd 압축 레이어는 검증된 바 없다.** NCR의 이미지는 전부 gzip이라 현재 해당 없음. 하이브리드
  조립을 제거하면서 조립 단계의 zstd 차단 가드도 함께 사라졌으므로, zstd 레이어가 섞인 이미지가
  NCR에 올라오면 차단 없이 반입된다. Docker 29(containerd)에서는 적재되는 것을 확인했으나
  24~28 구간은 미측정이다.
