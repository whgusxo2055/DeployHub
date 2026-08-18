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
import com.deployhub.version.service.VersionComparisonService;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /** 감사 로그 ({@code PackageCleanupService}와 같은 로거를 공유한다). */
    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

    private final PackageJobRepository packageJobRepository;
    private final PackageItemRepository packageItemRepository;
    private final MainVersionRepository mainVersionRepository;
    private final ComponentRepository componentRepository;
    private final VersionComparisonService versionComparisonService;
    private final PackagingEligibilityService packagingEligibilityService;
    private final String workDir;
    private final long minFreeDiskBytes;

    public PackageJobService(
            PackageJobRepository packageJobRepository,
            PackageItemRepository packageItemRepository,
            MainVersionRepository mainVersionRepository,
            ComponentRepository componentRepository,
            VersionComparisonService versionComparisonService,
            PackagingEligibilityService packagingEligibilityService,
            @Value("${deployhub.work-dir}") String workDir,
            @Value("${deployhub.job.min-free-disk-bytes}") long minFreeDiskBytes) {
        this.packageJobRepository = packageJobRepository;
        this.packageItemRepository = packageItemRepository;
        this.mainVersionRepository = mainVersionRepository;
        this.componentRepository = componentRepository;
        this.versionComparisonService = versionComparisonService;
        this.packagingEligibilityService = packagingEligibilityService;
        this.workDir = workDir;
        this.minFreeDiskBytes = minFreeDiskBytes;
    }

    /**
     * 매니페스트 확정 요청의 기본 선택값이지 선택 가능 범위가 아니다 — 선택 범위는 메인버전의
     * 전체 컴포넌트다(미변경분 포함). 전체 목록은 {@code GET /api/main-versions/{versionName}}이
     * {@code changed} 플래그와 함께 준다. 없는 메인버전이면 404.
     */
    public List<String> changedComponents(String versionName) {
        assertMainVersionExists(versionName);
        return versionComparisonService.changedImageTags(versionName);
    }

    /** 매니페스트 확정. 순서 고정 — 찌꺼기 행을 남기지 않도록 검증을 전부 마친 뒤에야 package_item을 재생성한다. */
    @Transactional
    public PackageJobDetailResponse create(String versionName, PackageJobCreateRequest request) {
        // 서브버전 upsert(SubVersionWriter)와 같은 행을 잡는다 — 없으면 두 트랜잭션이 각자
        // 검사를 통과해, 확정된 package_item과 DB 컴포넌트가 어긋난 채 패키징이 돈다.
        mainVersionRepository
                .lockByVersionName(versionName)
                .orElseThrow(() ->
                        new ApiException(ErrorCode.MAIN_VERSION_NOT_FOUND, List.of("versionName=" + versionName)));

        PackagingEligibility eligibility = packagingEligibilityService.evaluate(versionName);
        if (!eligibility.eligible()) {
            throw new ApiException(
                    ErrorCode.PACKAGING_BLOCKED_BY_PENDING, eligibility.blockingSubVersionCodes());
        }

        List<String> changedTags = versionComparisonService.changedImageTags(versionName);
        List<String> targetTags =
                (request.imageTags() == null || request.imageTags().isEmpty()) ? changedTags : request.imageTags();
        // 기준은 "선택 0건"이다 — 기본값(변경분)이 비어도 호출측이 imageTags를 명시하면 거부하지 않는다.
        if (targetTags.isEmpty()) {
            throw new ApiException(ErrorCode.NO_PACKAGING_TARGET);
        }
        assertTargetTagsValid(versionName, targetTags);

        // 락보다 먼저 한다 — 락을 쥔 채 파일시스템 I/O를 하면 WORK_DIR이 네트워크 스토리지일 때
        // DB 커넥션과 행 락이 무기한 묶인다.
        checkDiskSpace(request.force());
        PackageJob job = resolveJob(versionName, request.createdBy(), request.force());

        packageItemRepository.deleteByVersionName(versionName);
        // Hibernate는 같은 트랜잭션 내 INSERT를 DELETE보다 먼저 플러시한다 — 같은 image_tag를
        // 다시 쓰면 PK 충돌이 나므로 먼저 비운다.
        packageItemRepository.flush();
        for (String tag : targetTags) {
            packageItemRepository.save(
                    PackageItem.builder().versionName(versionName).imageTag(tag).build());
        }

        // package_job은 메인버전당 1건이라 force 재생성 시 이전 이력이 덮어써진다 —
        // 누가 무엇을 확정했는지는 감사 로그에만 남는다.
        AUDIT.info(
                "job-created versionName={} createdBy={} force={} imageTags={}",
                versionName,
                request.createdBy(),
                request.force(),
                targetTags);

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
     * 작업 디렉터리가 소실됐으면 {@code force=true}일 때만 전 항목을 되돌린다.
     * 재개는 이 트랜잭션이 커밋된 뒤 컨트롤러가 {@link JobOrchestrator#resume}으로 호출한다.
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
        boolean workDirLost = !Files.isDirectory(Path.of(workDir, versionName, "images"));
        if (workDirLost && !request.force()) {
            throw new ApiException(ErrorCode.WORK_DIR_LOST);
        }

        List<PackageItem> targets = resolveRetryTargets(allItems, request, workDirLost);
        // 되돌릴 항목이 없어도 태그를 지정하지 않은 요청은 통과시킨다 — 항목은 전부 성공했는데
        // Job 단위 실패(폴더 확보 실패 등)로 FAILED가 된 경우가 있고, 여기서 막으면 그 Job은
        // 영구 좌초한다(FAILED라 상태 전이도 못 하고 재시도도 못 한다). resume이 다운로드·업로드를
        // 다시 돌면서 이미 끝난 항목은 알아서 건너뛴다.
        boolean explicitTargets = request.imageTags() != null && !request.imageTags().isEmpty();
        if (targets.isEmpty() && (explicitTargets || allItems.isEmpty())) {
            throw new ApiException(ErrorCode.NO_PACKAGING_TARGET, List.of("target=retry"));
        }

        for (PackageItem item : targets) {
            item.resetForRetry();
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

    private List<PackageItem> resolveRetryTargets(List<PackageItem> allItems, PackageItemRetryRequest request, boolean workDirLost) {
        if (workDirLost) {
            return allItems; // force=true로만 여기 온다 — 전건 재수집
        }
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

    private void assertMainVersionExists(String versionName) {
        if (!mainVersionRepository.existsById(versionName)) {
            throw new ApiException(ErrorCode.MAIN_VERSION_NOT_FOUND, List.of("versionName=" + versionName));
        }
    }

    private void assertTargetTagsValid(String versionName, List<String> targetTags) {
        Set<String> unique = new HashSet<>(targetTags);
        if (unique.size() != targetTags.size()) {
            throw new ApiException(ErrorCode.INVALID_IMAGE_TAG_SELECTION, List.of("duplicated"));
        }

        // 등록 시점에 이미 문법을 강제하지만, 그 검증 이전에 저장된 행이 남아 있을 수 있다 —
        // 오염된 image_tag가 package_item으로 스냅샷되지 않게 한 번 더 거른다.
        for (String tag : targetTags) {
            try {
                ImageReference.parse(tag);
            } catch (IllegalArgumentException e) {
                // 예외 메시지에는 원문이 들어 있다 — 로그로만 남기고 응답에는 태그만 싣는다.
                log.warn("확정 대상 image_tag 형식 오류: versionName={}, reason={}", versionName, e.getMessage());
                throw new ApiException(ErrorCode.INVALID_IMAGE_TAG_SELECTION, List.of(tag));
            }
        }

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
     * 미삭제 DONE은 force일 때만, FAILED와 정리된 DONE은 항상, 진행 중 상태는 force로도 안 뚫린다.
     *
     * <p>존재 확인은 반드시 {@code existsById}로 할 것 — {@code findById}를 쓰면 엔티티가 1차 캐시에
     * 올라가 뒤따르는 {@code FOR UPDATE}가 stale 인스턴스를 돌려줘 락이 무력화된다.
     */
    private PackageJob resolveJob(String versionName, String createdBy, boolean force) {
        if (!packageJobRepository.existsById(versionName)) {
            try {
                return packageJobRepository.saveAndFlush(PackageJob.builder()
                        .versionName(versionName)
                        .createdBy(createdBy)
                        .build());
            } catch (DataIntegrityViolationException e) {
                throw translateJobInsertConflict(e);
            }
        }

        PackageJob existing = packageJobRepository
                .findByVersionName(versionName)
                .orElseThrow(() -> new ApiException(ErrorCode.JOB_CREATION_CONFLICT));

        boolean alreadyCleaned = existing.getDeletedAt() != null;
        boolean blocked =
                switch (existing.getStatus()) {
                    case DONE -> !alreadyCleaned && !force;
                    case FAILED -> false;
                    default -> true; // 진행 중 — force로도 안 뚫림
                };
        if (blocked) {
            throw new ApiException(
                    ErrorCode.DUPLICATE_PACKAGE_JOB,
                    List.of("status=" + existing.getStatus(), "spFolderUrl=" + existing.getSpFolderUrl()));
        }
        existing.resetForRerun(createdBy);
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

    /**
     * 설정된 여유 공간 기준값과만 비교한다(실제 소요량 기준 차단은 다운로드 단계가 따로 한다).
     * force=false면 409로 막고, force=true는 사용자가 확인한 것으로 보고 진행한다.
     * 디렉터리 생성이 실패하면 검사만 건너뛴다 — 그 실패로 확정 전체가 막혀선 안 된다.
     */
    private void checkDiskSpace(boolean force) {
        File dir = new File(workDir);
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("작업 디렉터리를 만들 수 없어 디스크 여유 공간 확인을 건너뜁니다: {}", workDir);
            return;
        }

        long usableBytes = dir.getUsableSpace();
        if (usableBytes >= minFreeDiskBytes) {
            return;
        }
        // 경로·여유 용량은 인프라 정보라 무인증 API 응답에 싣지 않는다 — 로그에만 남긴다.
        log.warn(
                "디스크 여유 공간 부족: workDir={}, usable={} bytes, threshold={} bytes, force={}",
                workDir,
                usableBytes,
                minFreeDiskBytes,
                force);
        if (!force) {
            // 경로·용량은 위 로그에만 남긴다 — details로도 인프라 정보를 내보내지 않는다.
            throw new ApiException(ErrorCode.INSUFFICIENT_DISK_SPACE);
        }
    }
}
