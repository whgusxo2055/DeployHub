package com.deployhub.job.service;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.job.dto.PackageItemRetryRequest;
import com.deployhub.job.dto.PackageItemResponse;
import com.deployhub.job.dto.PackageJobCreateRequest;
import com.deployhub.job.dto.PackageJobDetailResponse;
import com.deployhub.job.dto.PackageJobResponse;
import com.deployhub.job.entity.JobStatus;
import com.deployhub.job.entity.PackageItem;
import com.deployhub.job.entity.PackageItemStatus;
import com.deployhub.job.entity.PackageJob;
import com.deployhub.job.repository.PackageItemRepository;
import com.deployhub.job.repository.PackageJobRepository;
import com.deployhub.registry.ImageReference;
import com.deployhub.version.entity.Component;
import com.deployhub.version.repository.ComponentRepository;
import com.deployhub.version.repository.MainVersionRepository;
import com.deployhub.version.service.PackagingEligibility;
import com.deployhub.version.service.PackagingEligibilityService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Job 조회·매니페스트 확정·중복 방지. */
@Slf4j
@Service
@Transactional(readOnly = true)
public class PackageJobService {

    private final PackageJobRepository packageJobRepository;
    private final PackageItemRepository packageItemRepository;
    private final MainVersionRepository mainVersionRepository;
    private final ComponentRepository componentRepository;
    private final PackagingEligibilityService packagingEligibilityService;
    private final String workDir;

    public PackageJobService(
            PackageJobRepository packageJobRepository,
            PackageItemRepository packageItemRepository,
            MainVersionRepository mainVersionRepository,
            ComponentRepository componentRepository,
            PackagingEligibilityService packagingEligibilityService,
            @Value("${deployhub.work-dir}") String workDir) {
        this.packageJobRepository = packageJobRepository;
        this.packageItemRepository = packageItemRepository;
        this.mainVersionRepository = mainVersionRepository;
        this.componentRepository = componentRepository;
        this.packagingEligibilityService = packagingEligibilityService;
        this.workDir = workDir;
    }

    /** 매니페스트 확정. 순서 고정 — 찌꺼기 행을 남기지 않도록 검증을 전부 마친 뒤에야 package_item을 재생성한다. */
    @Transactional
    public PackageJobDetailResponse create(String versionName, PackageJobCreateRequest request) {
        // 서브버전 upsert(SubVersionWriter)와 같은 행을 잡아 "컴포넌트 수정"과 "매니페스트 확정"을
        // 직렬화한다. 존재 확인도 겸한다 — 락 없는 findById면 동시 생성 두 건이 둘 다 INSERT를 시도한다.
        mainVersionRepository
                .lockByVersionName(versionName)
                .orElseThrow(() ->
                        new ApiException(ErrorCode.MAIN_VERSION_NOT_FOUND, List.of("versionName=" + versionName)));

        List<String> targetTags = request.imageTags();

        // 서브버전 상태가 PENDING이면 패키징을 막는다
        assertPackagingAllowed(versionName, targetTags);

        // Job이 이미 존재하면 진행 중·완료 상태라 덮어쓰지 못한다 — DELETED·FAILED만 되돌릴 수 있다.
        PackageJob job = resolveJob(versionName);

        // 기존 항목을 모두 지우고 새로 생성한다 — 실패한 항목이 남아 있으면 재시도에서 tar를 재사용할 수 없으므로
        packageItemRepository.deleteByVersionName(versionName);
        // Hibernate는 같은 트랜잭션 내 INSERT를 DELETE보다 먼저 플러시한다 — 같은 image_tag를
        // 다시 쓰면 PK 충돌이 나므로 먼저 비운다.
        packageItemRepository.flush();

        for (String tag : targetTags) {
            packageItemRepository.save(PackageItem.builder().versionName(versionName).imageTag(tag).build());
        }

        log.info("job-created versionName={} imageTags={}", versionName, targetTags);

        return toDetail(job, versionName);
    }

    public PackageJobDetailResponse getDetail(String versionName) {
        PackageJob job = packageJobRepository.getOrThrow(versionName);
        return toDetail(job, versionName);
    }

    /** {@link JobOrchestrator}가 단계 전후로 호출하는 상태 전이 전용 메서드. */
    @Transactional
    public void changeStatus(String versionName, JobStatus status) {
        PackageJob job = packageJobRepository.getOrThrow(versionName);
        job.changeStatus(status);
    }

    /**
     * 마지막 전이. FAILED 항목이 하나라도 남아 있으면 DONE 대신 FAILED로 끝낸다 — 부분 재시도는
     * 지정한 태그만 PENDING으로 되돌리므로 나머지 FAILED는 다운로드(PENDING만)·업로드
     * (DOWNLOADED/UPLOADED만) 양쪽에서 스킵된 채 여기 도달한다. DONE으로 끝내면 그 항목은
     * 영구 복구 불가다 — {@link #retry}가 FAILED인 Job만 받고, 정리 배치가 로컬 tar를 지운다.
     */
    @Transactional
    public void finish(String versionName) {
        PackageJob job = packageJobRepository.getOrThrow(versionName);
        boolean anyFailed = packageItemRepository.findByVersionNameOrderByImageTagAsc(versionName).stream()
                .anyMatch(item -> item.getStatus() == PackageItemStatus.FAILED);
        if (anyFailed) {
            log.warn("Job '{}'에 FAILED 항목이 남아 DONE 대신 FAILED로 종료합니다.", versionName);
        }
        job.changeStatus(anyFailed ? JobStatus.FAILED : JobStatus.DONE);
    }

    /**
     * 폴더 확보 후 {@code GraphFolderService}가 호출한다. 엔티티 변경이 이 {@code @Transactional}
     * 메서드를 거쳐야 한다 — 리포지토리를 직접 만지면 detached 인스턴스 merge라 동시 변경을 조용히 덮어쓴다.
     */
    @Transactional
    public void applyFolder(String versionName, String spFolderId, String spFolderUrl) {
        PackageJob job = packageJobRepository.getOrThrow(versionName);
        job.applyFolder(spFolderId, spFolderUrl);
    }

    /**
     * 수동 재시도. {@code imageTags}가 비면 FAILED 전체가 대상이고, DOWNLOADED/UPLOADED는 지정해도 제외된다.
     * 되돌릴 FAILED가 없어도 태그 미지정이면 단계 재개만 시킨다(Job 단위 실패 복구).
     * 업로드 단계에서 죽은 항목은 받아 둔 tar가 그대로면 DOWNLOADED로 되돌려 재수집을 건너뛴다.
     * 재개는 이 트랜잭션이 커밋된 뒤 컨트롤러가 {@link JobOrchestrator#startValidated}로 호출한다.
     */
    @Transactional
    public PackageJobDetailResponse retry(String versionName, PackageItemRetryRequest request) {
        // 락 없는 findById를 쓰면 동시 재시도 두 건이 모두 FAILED를 보고 통과해
        // 같은 tarPath에 두 워커가 동시에 쓰게 된다.
        PackageJob job = packageJobRepository.lockOrThrow(versionName);
        if (job.getStatus() != JobStatus.FAILED) {
            throw new ApiException(ErrorCode.RETRY_REJECTED_JOB_NOT_FAILED);
        }

        List<PackageItem> allItems = packageItemRepository.findByVersionNameOrderByImageTagAsc(versionName);
        // 작업 디렉터리는 다운로드가 시작될 때 만들어진다 — VALIDATING에서 죽은 Job은 애초에 없으므로
        // 부재를 "소실"로 보면 실패 원인과 무관한 E-0703이 나간다.
        boolean expectsTars = allItems.stream()
                .anyMatch(item -> item.getStatus() == PackageItemStatus.DOWNLOADED
                        || item.getStatus() == PackageItemStatus.UPLOADED);
        if (expectsTars && !Files.isDirectory(Path.of(workDir, versionName, "images"))) {
            throw new ApiException(ErrorCode.WORK_DIR_LOST);
        }

        List<PackageItem> targets = resolveRetryTargets(allItems, request);
        // 되돌릴 항목이 없어도 태그를 지정하지 않은 요청은 통과시킨다 — 항목은 전부 성공했는데
        // Job 단위 실패(폴더 확보 실패 등)로 FAILED가 된 경우가 있고, 여기서 막으면 그 Job은
        // 영구 좌초한다(FAILED라 상태 전이도 못 하고 재시도도 못 한다). 재개가 다운로드·업로드를
        // 다시 돌면서 이미 끝난 항목은 알아서 건너뛴다.
        boolean explicitTargets = request.imageTags() != null && !request.imageTags().isEmpty();
        if (targets.isEmpty() && (explicitTargets || allItems.isEmpty())) {
            throw new ApiException(ErrorCode.NO_PACKAGING_TARGET, List.of("target=retry"));
        }

        for (PackageItem item : targets) {
            Long downloadedSize = item.isFailedAfterDownload() ? item.getFileSize() : null;
            item.resetForRetry();
            if (downloadedSize != null && localTarMatches(versionName, item, downloadedSize)) {
                item.markDownloaded(downloadedSize);
            }
        }
        packageItemRepository.saveAll(targets);
        job.changeStatus(JobStatus.DOWNLOADING);

        return toDetail(job, versionName);
    }

    private PackageJobDetailResponse toDetail(PackageJob job, String versionName) {
        List<PackageItem> items = packageItemRepository.findByVersionNameOrderByImageTagAsc(versionName);
        return PackageJobDetailResponse.builder()
                .job(PackageJobResponse.of(job, items))
                .items(items.stream().map(PackageItemResponse::from).toList())
                .build();
    }

    /** 크기까지 같아야 인정한다 — 경로·형식 문제는 "없음"으로 보고 다시 받게 한다. */
    private boolean localTarMatches(String versionName, PackageItem item, long expectedSize) {
        try {
            String fileName = ImageReference.parse(item.getImageTag()).tarFileName();
            return Files.size(Path.of(workDir, versionName, "images", fileName)) == expectedSize;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private List<PackageItem> resolveRetryTargets(List<PackageItem> allItems, PackageItemRetryRequest request) {
        if (request.imageTags() == null || request.imageTags().isEmpty()) {
            return allItems.stream().filter(item -> item.getStatus() == PackageItemStatus.FAILED).toList();
        }
        Set<String> requested = new HashSet<>(request.imageTags());
        return allItems.stream()
                .filter(item -> requested.contains(item.getImageTag()) && item.getStatus() == PackageItemStatus.FAILED)
                .toList();
    }

    public List<PackageJobResponse> list(JobStatus statusFilter) {
        List<PackageJob> jobs = packageJobRepository.findByStatusInOrderByCreatedAtDesc(
                statusFilter == null ? EnumSet.allOf(JobStatus.class) : EnumSet.of(statusFilter));
        if (jobs.isEmpty()) {
            return List.of();
        }

        List<String> versionNames = jobs.stream().map(PackageJob::getVersionName).toList();
        Map<String, List<PackageItem>> itemsByVersionName = packageItemRepository
                .findByVersionNameIn(versionNames)
                .stream()
                .collect(Collectors.groupingBy(PackageItem::getVersionName));

        return jobs.stream()
                .map(job -> PackageJobResponse.of(job, itemsByVersionName.getOrDefault(job.getVersionName(), List.of())))
                .toList();
    }

    /**
     * 락 없는 사전 검사 — 레지스트리 조회(태그당 3왕복, 최대 500건) 앞에서 싼 것부터 떨어뜨린다.
     * 통과해도 확정이 아니다: {@link #create}가 같은 검사를 main_version 락 안에서 다시 본다.
     */
    public void assertCreatable(String versionName, List<String> targetTags) {
        // 락 걸린 조회 앞이라 findById를 쓰지 않는다 — 1차 캐시가 stale 인스턴스를 돌려주면 락이 무력해진다.
        if (!mainVersionRepository.existsById(versionName)) {
            throw new ApiException(ErrorCode.MAIN_VERSION_NOT_FOUND, List.of("versionName=" + versionName));
        }
        assertPackagingAllowed(versionName, targetTags);
    }

    private void assertPackagingAllowed(String versionName, List<String> targetTags) {
        //SubVersions의 상태가 PENDING이면 패키징을 막는다.
        PackagingEligibility eligibility = packagingEligibilityService.evaluate(versionName);
        if (!eligibility.eligible()) {
            throw new ApiException(ErrorCode.PACKAGING_BLOCKED_BY_PENDING, eligibility.blockingSubVersionCodes());
        }

        assertTargetTagsValid(versionName, targetTags);
    }
    /** 주어진 태그들이 유효한지 검증한다. */
    private void assertTargetTagsValid(String versionName, List<String> targetTags) {
        Map<String, String> tagByFileName = new HashMap<>();
        for (String tag : targetTags) {
            String fileName;
            try {
                fileName = ImageReference.parse(tag).tarFileName();
            } catch (IllegalArgumentException e) {
                log.warn("확정 대상 image_tag 형식 오류: versionName={}, reason={}", versionName, e.getMessage());
                throw new ApiException(ErrorCode.INVALID_IMAGE_TAG_SELECTION, List.of(tag));
            }

            //파일명 중복 검증
            String previous = tagByFileName.putIfAbsent(fileName, tag);
            if (previous != null) {
                throw new ApiException(
                        ErrorCode.INVALID_IMAGE_TAG_SELECTION,
                        List.of(previous.equals(tag) ? "duplicated" : "fileNameCollision", tag));
            }
        }
        // 메인버전의 Component에 등록된 태그만 허용한다 — 레지스트리 확인은 Job이 커밋된 뒤 워커가 한다.
        Set<String> validTags = componentRepository.findByMainVersionName(versionName).stream()
                .map(Component::getImageTag)
                .collect(Collectors.toSet());
        List<String> unknownTags = targetTags.stream().filter(tag -> !validTags.contains(tag)).toList();
        if (!unknownTags.isEmpty()) {
            List<String> shown = unknownTags.stream().limit(20).toList();
            throw new ApiException(
                    ErrorCode.INVALID_IMAGE_TAG_SELECTION, shown);
        }
    }

    /**
     * 중복 확인. 행이 없으면 신규 생성하고, 있으면 락을 잡고 재사용 가능 여부를 판정한다 —
     * 되돌릴 수 있는 건 FAILED와 DELETED뿐이다. 완료된 Job은 패키지를 정리(DELETED)한 뒤 다시 만든다.
     *
     * <p>존재 확인은 반드시 {@code existsById}로 할 것 — {@code findById}를 쓰면 엔티티가 1차 캐시에
     * 올라가 뒤따르는 {@code FOR UPDATE}가 stale 인스턴스를 돌려줘 락이 무력화된다.
     */
    private PackageJob resolveJob(String versionName) {
        // 존재하지 않으면 신규 생성 — 충돌은 PK 유니크 위반으로 잡는다.
        if (!packageJobRepository.existsById(versionName)) {
            try {
                return packageJobRepository.saveAndFlush(
                        PackageJob.builder().versionName(versionName).build());
            } catch (DataIntegrityViolationException e) {
                throw translateJobInsertConflict(e);
            }
        }

        // 존재하면 락을 잡고 재사용 가능 여부를 판정한다 — DELETED·FAILED만 되돌릴 수 있다.
        PackageJob existing = packageJobRepository
                .findByVersionName(versionName)
                .orElseThrow(() -> new ApiException(ErrorCode.JOB_CREATION_CONFLICT));

        // 정리된 Job은 내려받을 산출물이 없어 덮어쓸 것도 없다.
        boolean blocked = switch (existing.getStatus()) {
            case DELETED, FAILED -> false;
            default -> true; // DONE·진행 중 — 살아 있는 산출물을 덮어쓰지 않는다
        };

        // Job이 살아 있으면 폴더가 이미 확보돼 있어 덮어쓰면 안 된다
        if (blocked) {
            throw new ApiException(
                    ErrorCode.DUPLICATE_PACKAGE_JOB,
                    List.of("status=" + existing.getStatus(), "spFolderUrl=" + existing.getSpFolderUrl()));
        }
        existing.resetForRerun();
        return existing;
    }

    /**
     * 실제 PK 유니크 위반(MySQL 1062)일 때만 충돌로 번역한다 — 다른 제약 위반까지 뭉뚱그리면
     * "다시 시도하세요"라는 잘못된 안내가 나간다.
     */
    private RuntimeException translateJobInsertConflict(DataIntegrityViolationException e) {
        Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(e);
        if (rootCause instanceof SQLIntegrityConstraintViolationException sqlEx && sqlEx.getErrorCode() == 1062) {
            return new ApiException(ErrorCode.JOB_CREATION_CONFLICT);
        }
        return e;
    }

}
