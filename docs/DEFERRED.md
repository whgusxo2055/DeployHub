# 알고도 미룬 것

실물 검증 없이는 고쳐도 맞는지 알 수 없거나, 지금 손대면 근거보다 추측이 앞서는 항목들이다.
**문제가 실제로 발생하면 그때 아래 순서로 판단한다.** 각 항목은 "증상 → 원인 → 그때 할 일"
순으로 적었다. 코드에 남긴 `ponytail:` 주석과 같은 성격이지만, 이쪽은 여러 파일에 걸쳐 있다.

작성: 2026-08-18 (다중 에이전트 코드리뷰 결과 중 미적용분)

---

## 0. 먼저 해야 할 실물 검증 — **완료(2026-08-21)**

아래 1·2번은 수백 MB짜리 tar를 실제로 올려 보기 전까지 판단이 갈리지 않는 항목이었다.
2026-08-21에 **10.7GB(`pips:1.0.15.0200`)를 테스트 서버에서 실제로 업로드**해 확인했다.

| 항목 | 결과 |
| --- | --- |
| 청크 수 | 약 1,070개 (`UPLOAD_CHUNK_SIZE` 10MB) |
| 소요 | 약 20분 (Job 전체는 다운로드 포함 78분) |
| 청크 타임아웃 | **0회** (`retryCount=0`, 416/410 로그 없음) |

`UPLOAD_REQUEST_TIMEOUT` 300초는 이 회선에서 10MB 청크에 충분하다. **따라서 1·2번은 현재
구성에서 이론값이고 우선순위가 낮다.** 회선이 느린 환경으로 옮기거나 청크 크기를 키우면
다시 판단할 것 — 그때 확인할 것은 타임아웃이 어느 청크에서 나는지다(마지막 청크인지 여부가
1번의 분기다).

---

## 1. 마지막 청크 타임아웃은 재전송해도 416이 오지 않는다

**증상** — 업로드가 100% 끝난 직후 GB 단위 파일이 통째로 다시 올라간다. 결과는 맞고
(`conflictBehavior=replace`) 비용만 최악이다.

**원인** — `GraphUploadService.putChunkWithRetry`는 타임아웃 시 같은 Range를 재전송하고,
"서버가 이미 받았다면 416이 오고 `uploadFile`의 재개 경로가 오프셋을 맞춘다"를 전제한다.
이 전제가 **마지막 청크에서만** 성립하지 않는다 — Graph는 마지막 청크를 받으면 파일을
확정하고 업로드 세션을 삭제한다. 응답만 데드라인에 잘려 유실되면 재전송은 없어진 세션으로
가서 404/410을 받고, `uploadFile`이 "세션 소멸"로 판정해 `uploadItemWithRetry`가 새 세션으로
파일을 처음부터 다시 올린다. 하필 마지막 청크 응답은 서버가 파일 전체를 조립하는 구간이라
데드라인에 걸릴 확률이 가장 높다.

**그때 할 일** — 타임아웃 재전송 직전에 `getUploadSessionStatus`를 한 번 쳐서 오프셋을
확인한다(416 경로와 코드가 합쳐진다). 세션이 404면 "이미 완료"로 보고 폴더 child 조회로
`webUrl`만 받아오면 재업로드가 사라진다.

**미검증** — 비-마지막 청크의 416 응답 자체는 Graph 문서 기준이고 실 테넌트로 확인한 적이
없다. `MAX_RANGE_MISMATCH_RETRIES`의 기존 `ponytail:` 주석과 같은 등급으로 본다.

---

## 2. 청크 재시도와 파일 재시도가 곱해져 최악 정지 시간이 길다

**증상** — Graph가 응답을 붙들고만 있으면 패키징 파이프라인이 몇 시간 단위로 멈춘다.
`jobExecutor`는 고정 3스레드라 그동안 다른 Job도 적체된다.

**원인** — 청크 재시도(최대 4회 × 300초 + 백오프 ≈ 21분)를 소진하면
`RetryableCallException`이 그대로 올라가는데, `GraphUploadService.isRetryable`이 이걸
`ApiException`이 아니라는 이유로 재시도 가능으로 판정해 **파일 단위 재시도 4회가 다시 곱해진다**
(항목 1개 최악 ≈ 84분). read-timeout이 10초이던 시절 대비 최악 정지가 약 30배다.

무인증 API지만 외부 공격 표면은 아니다 — 이 경로는 `@Async("jobExecutor")` 워커 전용이고
서블릿 스레드를 잡지 않는다(보안 리뷰에서 호출 그래프로 확인).

**그때 할 일** — 청크 단위 재시도가 이미 소진된 실패는 파일 단위 재시도로 되살릴 성질이
아니다. `isRetryable`에서 `RetryableCallException`을 걸러 항목을 바로 실패시키거나, 파일 단위
재시도 예산을 1로 낮춘다. 항목/Job 단위 wall-clock 상한이 필요해지면 그때 추가한다.

---

## 3. `finish()`가 행 락 없이 상태를 읽고 갱신한다

**증상** — 아직 없다. 재현 인터리빙을 두 리뷰어 모두 구성하지 못했다.

**원인** — `PackageJobService.finish`는 `getOrThrow`(락 없음)로 Job을 잡고 `package_item`
상태를 읽어 그 결과로 Job 상태를 전이시킨다. CLAUDE.md의 "상태를 보고 갱신하는 경로는 락 걸린
리포지토리 메서드를 쓸 것"과 어긋나고, 같은 클래스의 `retry()`는 `lockOrThrow`를 쓴다.

현재는 `finish` 실행 시점의 상태가 항상 `UPLOADING`이고 `retry()`는 `FAILED`만 받으므로
경합 상대가 게이트에서 걸린다. 다만 `getOrThrow`/`lockOrThrow`를 일부러 분리해 둔 코드베이스에
"다음 사람이 락 없는 쪽을 고른다"는 그 함정에 새 메서드가 그대로 빠진 형태다.

**그때 할 일** — `lockOrThrow`로 바꾼다(한 줄). 상태 전이 경로를 추가로 만들 일이 생기면
그때 함께 정리하는 편이 낫다.

---

## 4. 부분 성공 Job이 FAILED로 끝나면 매니페스트 잠금이 풀린다

**증상** — 항목 대부분이 SharePoint에 올라간 Job이 매니페스트 수정을 막지 않는다. 그 사이
컴포넌트를 고쳐도 `retry()`는 확정 시점의 `package_item` 스냅샷으로 재개하므로 DB와 산출물이
갈릴 수 있다.

**원인** — `PackageJob.blocksManifestModification()`이 `FAILED`만 예외로 둔다. 이번에 추가한
`finish()`가 "FAILED 항목이 남으면 FAILED로 종료"시키므로, 예전이라면 DONE이던 Job이 이제
FAILED로 끝나면서 잠금을 놓는다.

**그때 할 일** — 스냅샷 시맨틱상 의도된 동작일 수도 있어 판단이 필요하다. 조인다면
`blocksManifestModification()`을 "FAILED면서 항목이 전부 FAILED/PENDING일 때만 false"로 좁힌다.

---

## 5. 무인증 `/retry` 한 번이 전량 재업로드를 유발할 수 있다

**증상** — 항목이 전부 UPLOADED인 FAILED Job에 태그 없이 `/retry`를 치면 수십 GB 재업로드와
SharePoint 폴더 비우기가 일어난다. 종전에는 `NO_PACKAGING_TARGET`으로 막히던 호출이다.

**원인** — Job 단위 실패(폴더 확보 실패 등)로 영구 좌초하던 문제를 풀면서 "대상 0건이어도
태그 미지정이면 통과"로 완화했다. 사내망 격리 + 무인증은 이미 수용된 리스크라 보안 리뷰도
수용 가능으로 판정했다. 무한 루프는 성립하지 않는다 — 재업로드가 성공하면 `finish()`가 DONE으로
끝내 더는 retry가 안 되고, 도중 실패하면 항목이 FAILED가 돼 기존 경로로 돌아간다.

**그때 할 일** — 조인다면 이 분기에만 `force=true`를 요구하는 게 가장 싼 가드다.

---

## 6. 같은 담당 영역을 동시에 고치면 나중 요청이 이긴다 (낙관적 락 없음)

**증상** — 한 담당자가 두 탭을 열어 두거나 계정을 공유해 같은 `code`를 동시에 저장하면,
먼저 저장한 값이 조용히 덮인다. 서버는 409를 주지 않고 둘 다 200으로 끝난다.

**원인** — `SubVersionWriter.save`는 `main_version` 행 락으로 두 요청을 **직렬화**할 뿐,
"당신이 본 값이 아직 최신인가"는 묻지 않는다. `sub_version`에 `@Version` 컬럼이 없다.

지금은 거의 성립하지 않는다 — 담당 영역이 `UNIQUE(main_version_name, code)`로 1:1이고,
서브버전 등록·수정 API가 경로에 `code`를 박은 단건(`PUT /api/main-versions/{v}/sub-versions/{code}`)
하나뿐이라 서로 다른 담당자의 요청이 같은 행을 겨냥할 수 없다. 일괄 등록 API를 없앤 이유가
이것이다 — 화면 전체를 실어 보내는 구현을 유도해, 자기가 건드리지 않은 담당 영역까지
요청 시점 값으로 되돌리는 경로였다.

덮여도 조용하지는 않다. 값이 바뀌면 `SubVersion.update`가 `submit_status`를 PENDING으로
되돌리므로, 그 담당 영역은 다시 확인 대기가 되고 패키징이 막힌다.

**그때 할 일** — `sub_version`에 `row_version` 컬럼(`version`은 모듈 릴리즈 버전이라 이름이
겹친다)을 추가하고 `@Version`을 붙인다. 요청 DTO에 `rowVersion`을 실어 보내고
`OptimisticLockingFailureException`을 409로 매핑한다.

**주의** — 이것만으로는 컴포넌트 전용 변경이 안 걸린다. `image_tag` 목록만 바뀌는 수정은
`component` 테이블 DELETE/INSERT라 `sub_version` 행을 안 건드릴 수 있고(`resetSubmitStatus`가
이미 PENDING이면 dirty 체크에 안 잡힌다) `row_version`이 안 오른다. 거기까지 막으려면
`LockModeType.OPTIMISTIC_FORCE_INCREMENT`가 필요하다.

---

## 7. 담당자가 `sortOrder`를 실어 보내야 해서 문서 표기 순서를 되돌릴 수 있다

**증상** — 담당자가 자기 담당 영역을 저장한 뒤 메인버전 상세의 표기 순서가 옛날로 돌아간다.
`SubVersion.update`가 `sortOrder` 변경도 "변경"으로 보므로 `submit_status`까지 PENDING으로
되돌아간다.

**원인** — `SubVersionUpsertRequest.sortOrder`가 `@NotNull`이라 단건 PUT에도 반드시 실린다.
`sortOrder`는 문서 표기 순서라 담당자가 알 바가 아닌데, 그 사이 등록자가 순서를 바꿨으면
담당자의 stale 값이 이긴다.

현실적으로는 프론트가 조회한 값을 그대로 왕복시키면 드러나지 않는다. 다만 "변경 없음" 경로에서는
이제 조용히 넘어가지 않는다 — 등록자가 그 사이 순서를 바꿨으면 담당자의 stale `sortOrder`가 "필드
변경"으로 잡혀 E-0208이 난다. 되돌림 대신 400이 뜨므로 이 갭의 위험 구간은 `PENDING`/`UPDATED`를
보내는 경로로 좁아졌다.

**그때 할 일** — 단건 PUT에서 `sortOrder`를 무시하고 기존 값을 유지한다. 지금은 최초 입력
경로와 DTO를 공유하고 있어(신규 생성 시엔 `sortOrder`가 필요하다) 분리 비용이 붙는다 —
`sortOrder`를 nullable로 바꾸고 "null이면 기존 값 유지, 신규면 마지막+1"로 가는 게
DTO를 쪼개는 것보다 싸다.

---

## 8. 등록 요청 하나가 최악 18~30분 동안 `manifestExecutor`를 점유한다

**증상** — 서브버전 등록·수정(`PUT .../sub-versions/{code}`)이 수십 분 걸리고, 그동안 진행 중인
Job의 `VALIDATING` 단계가 큐에서 대기한다. 큐(200)가 넘치면 `RejectedExecutionException`이
`ApiException`이 아니라 500(E-9000)으로 나간다.

**원인** — 등록 시점 레지스트리 확인이 servlet 스레드에서 동기로 돌고 재시도가 붙는다.
`read-timeout` 10초(JDK 클라이언트에서 요청 전체 데드라인) × attempt당 HTTP 2~3회(Basic → 토큰 →
Bearer, 토큰 캐시 없음) ≈ 30초/attempt, `RetryExecutor` 총 4회 + backoff(5·15·45초) ≈ 태그 1건
최악 185초. 태그 30건 / 동시 5 → 배치 6개가 순차로 돈다. `manifestExecutor`는 풀 5 고정이고
`PackageValidationService`와 공유한다(NCR 전역 상한 목적).

**그때 할 일** — 이 경로는 이미 "확인 불가면 통과" 정책이라 재시도 4회가 필요 없다.
등록 확인만 재시도 1회로 낮추면 최악이 3분대로 줄고 코드도 줄어든다.

---

## 9. 요청 본문 크기 상한이 없다

**증상** — 무인증 API에 수백 MB짜리 JSON을 보내면 힙에 먼저 올라간다.

**원인** — `application.yml`에 `server.tomcat.max-*`가 없고 필터도 없다. Tomcat `maxPostSize`는
form-encoded 파라미터에만 적용되어 JSON 본문에는 무관하다. `@Size(max=30)`·`@Size(max=10000)`은
**역직렬화 후** 검사라 방어가 되지 않는다.

**그때 할 일** — 앞단 프록시의 `client_max_body_size`가 가장 싸다(앱 코드 0줄). 프록시가 없는
배치라면 `server.tomcat.max-swallow-size`가 아니라 `spring.servlet.multipart` 밖의 별도 필터가 필요하다.

---

## 10. 무인증 호출자가 서버의 NCR 자격증명으로 태그 존재를 열거할 수 있다

**증상** — `PUT .../sub-versions/{code}`에 확실히 없는 태그 하나를 섞어 30건씩 보내면, 저장 전에
E-0206으로 중단되고 `details`에 없는 태그 목록이 실린다 → 부작용 0인 "이 태그가 NCR에 있는가" oracle.
요청 1건당 업스트림 최대 ~90회 증폭이기도 하다.

**원인** — 처리 순서가 `existsById` → 락 가드 → 형식 검사 → **레지스트리 조회** → 저장이라,
저장에 실패해도 조회는 이미 끝난다. 그게 E-0206의 설계 의도(등록자에게 즉시 오타를 돌려준다)다.

**그때 할 일** — 사내망 무인증은 수용된 리스크지만 그 범위는 DeployHub 자체 데이터였고, 여기서는
서버가 보유한 NCR 자격증명이 임의 호출자에게 대여된다. 조인다면 이 경로에만 호출자별 레이트 리밋을
걸거나, 확인을 저장 이후 비동기로 미룬다(즉시 피드백은 포기).

---

## 11. `purge`가 행 락을 쥔 채 Graph DELETE를 한다

**증상** — 정리(`DELETE .../package`, 보존 배치)가 도는 동안 같은 Job을 건드리는 다른 요청이
`Lock wait timeout exceeded`(SQL 1205)로 죽는다. 2026-08-21 실제로 발생해 HTTP 요청 하나가
500(E-9000)으로 나갔다.

**원인** — OneDrive 계정을 바꾸자 DB의 `sp_folder_id`가 옛 계정 드라이브의 id인 채로 남았고,
`PackagePurgeService.purge`가 그 id를 **현재 드라이브**에 대해 삭제하려 했다. 이때 Graph가
404가 아니라 **500**을 준다 — `GraphApiClient.delete`는 404만 "이미 지워짐"으로 삼키므로 500은
일시 장애로 분류돼 재시도 3회(5+15+45 = 약 65초)를 돈다. 그런데 `purge`는 `@Transactional`이고
`lockOrThrow`(`SELECT ... FOR UPDATE`)로 행 락을 먼저 잡아, 그 65초 내내 락이 유지된다.
`innodb_lock_wait_timeout` 기본값이 50초라 겹친 요청이 먼저 죽는다.

실측(2026-08-21, 서버에서 새 계정 토큰으로 직접 조회):

| 요청 | 응답 |
| --- | --- |
| 다른 드라이브의 item id 조회 | `500 generalException` |
| 같은 드라이브의 없는 item id 조회 | `404 itemNotFound` |

계정 전환과 무관하게도 Graph가 삭제에 5xx를 주기만 하면 같은 증상이 난다. 클래스 javadoc의
`ponytail:` 메모가 이 상황을 예고하고 있었다.

**지금 상태** — 방아쇠만 제거했다. 옛 계정을 가리키던 `sp_folder_id`/`sp_folder_url`을 DB에서
비워, 죽은 id로 삭제를 쏘지 않는다. 원인 코드는 그대로다.

**안 고치기로 한 이유(2026-08-21 결정)** — 지금 `/me/drive`(OneDrive 개인 드라이브)를 쓰는 건
테스트 구성이고, Graph Sites 방식으로 옮기면 드라이브가 고정돼 "옛 드라이브의 item id" 상황
자체가 성립하지 않는다. 그래서 `sp_drive_id` 컬럼은 추가하지 않는다.

**그때 할 일** — Sites 전환 뒤에도 락 유지가 문제되면(Graph 5xx·수 GB 재귀 삭제) 삭제를 락 밖으로
빼고 트랜잭션은 재확인 + `deleted_at` 기록만 남긴다. 단 **락을 놓으면 새 경합이 생긴다** —
`PackageJobService.resolveJob`이 `deleted_at`이 찍힌 DONE Job과 FAILED Job의 재실행을 막지 않으므로,
삭제가 도는 사이 `force` 재실행이 끼어들면 방금 만들어진 폴더를 지울 수 있다. 사후 감지는
`finished_at` 비교로 되지만 삭제 자체는 이미 일어난 뒤다. 한 번 구현했다가 이 맞바꿈 때문에
되돌렸다.
