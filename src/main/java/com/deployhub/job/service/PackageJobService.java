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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Job 조회 + 매니페스트 확정(FN-03) + 중복 방지(FN-11) (구현계획서 Phase 3-1·3). */
@Slf4j
@Service
@Transactional(readOnly = true)
public class PackageJobService {

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

    /** 매니페스트 확정 요청의 기본값 후보(FN-03). 존재하지 않는 메인버전이면 404. */
    public List<String> changedComponents(String versionName) {
        assertMainVersionExists(versionName);
        return versionComparisonService.changedImageTags(versionName);
    }

    /**
     * FN-03 매니페스트 확정 + FN-11 중복 방지. 순서 고정 — 실패 시 찌꺼기 행을 남기지
     * 않도록 검증을 전부 마친 뒤에야 package_item을 재생성한다.
     */
    @Transactional
    public PackageJobDetailResponse create(String versionName, PackageJobCreateRequest request) {
        assertMainVersionExists(versionName);

        PackagingEligibility eligibility = packagingEligibilityService.evaluate(versionName);
        if (!eligibility.eligible()) {
            throw new ApiException(
                    ErrorCode.PACKAGING_BLOCKED_BY_PENDING,
                    ErrorCode.PACKAGING_BLOCKED_BY_PENDING.getDefaultMessage(),
                    eligibility.blockingSubVersionCodes());
        }

        List<String> changedTags = versionComparisonService.changedImageTags(versionName);
        List<String> targetTags =
                (request.imageTags() == null || request.imageTags().isEmpty()) ? changedTags : request.imageTags();
        // "선택 0건"(구현계획서 E-0303 정의, 429행)이 기준이다. 기본값(변경분)이 비어도
        // 호출측이 imageTags[]를 명시하면 그 목록이 최종 기준이라 거부하지 않는다.
        if (targetTags.isEmpty()) {
            throw new ApiException(
                    ErrorCode.NO_PACKAGING_TARGET, "패키징할 대상이 없습니다 (직전 메인버전 대비 변경된 컴포넌트가 없습니다).");
        }
        assertTargetTagsValid(versionName, targetTags);

        // 디스크 확인은 Job 행에 의존하지 않으므로 락(resolveJob)보다 먼저 한다 — 락을
        // 쥔 채로 파일시스템 I/O(mkdirs/getUsableSpace)를 하면 WORK_DIR이 네트워크
        // 스토리지일 때 DB 커넥션·행 락이 무기한 묶일 수 있다.
        checkDiskSpace(request.force());
        PackageJob job = resolveJob(versionName, request.createdBy(), request.force());

        packageItemRepository.deleteByVersionName(versionName);
        // Hibernate는 같은 트랜잭션 내 INSERT를 DELETE보다 먼저 플러시한다. 재사용 시
        // 이전과 같은 image_tag를 다시 쓰면 PK 충돌이 나므로 먼저 비운다
        // (SubVersionService.upsertAll과 동일한 문제).
        packageItemRepository.flush();
        for (String tag : targetTags) {
            packageItemRepository.save(
                    PackageItem.builder().versionName(versionName).imageTag(tag).build());
        }

        List<PackageItem> items = packageItemRepository.findByVersionNameOrderByImageTagAsc(versionName);
        return PackageJobDetailResponse.builder()
                .job(PackageJobResponse.of(job, items))
                .items(items.stream().map(PackageItemResponse::from).toList())
                .build();
    }

    public PackageJobDetailResponse getDetail(String versionName) {
        PackageJob job = packageJobRepository
                .findById(versionName)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.PACKAGE_JOB_NOT_FOUND, "메인버전 '%s'의 패키지 Job이 없습니다.".formatted(versionName)));
        List<PackageItem> items = packageItemRepository.findByVersionNameOrderByImageTagAsc(versionName);

        return PackageJobDetailResponse.builder()
                .job(PackageJobResponse.of(job, items))
                .items(items.stream().map(PackageItemResponse::from).toList())
                .build();
    }

    /** {@link JobOrchestrator}가 각 단계 전후로 호출하는 상태 전이 전용 메서드. */
    @Transactional
    public void changeStatus(String versionName, JobStatus status) {
        PackageJob job = packageJobRepository
                .findById(versionName)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.PACKAGE_JOB_NOT_FOUND, "메인버전 '%s'의 패키지 Job이 없습니다.".formatted(versionName)));
        job.changeStatus(status);
    }

    /**
     * FN-07 수동 재시도. {@code imageTags}를 지정하지 않으면 FAILED 전체가 대상이다 —
     * DOWNLOADED/UPLOADED 항목은 지정해도 대상에서 제외한다(구현계획서 489행). 작업
     * 디렉터리 자체가 소실됐으면(E-0703) {@code force=true}일 때만 전 항목을 재수집
     * 대상으로 되돌린다. 재개는 {@link JobOrchestrator#resume(String)}이 맡는다 — 이
     * 트랜잭션 커밋 후 컨트롤러가 호출한다(FN-03 create()와 동일한 이유).
     */
    @Transactional
    public PackageJobDetailResponse retry(String versionName, PackageItemRetryRequest request) {
        // findById(락 없음)를 쓰면 동시 재시도 요청 두 건이 모두 FAILED를 보고 통과해
        // resume()이 두 번 제출된다 — 같은 tarPath에 두 워커가 동시에 쓰게 된다
        // (resolveJob의 동시성 방어와 같은 이유, PackageJobRepository.findByVersionName 참고).
        PackageJob job = packageJobRepository
                .findByVersionName(versionName)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.PACKAGE_JOB_NOT_FOUND, "메인버전 '%s'의 패키지 Job이 없습니다.".formatted(versionName)));
        if (job.getStatus() != JobStatus.FAILED) {
            throw new ApiException(ErrorCode.RETRY_REJECTED_JOB_NOT_FAILED);
        }

        boolean workDirLost = !Files.isDirectory(Path.of(workDir, versionName, "images"));
        if (workDirLost && !request.force()) {
            throw new ApiException(ErrorCode.WORK_DIR_LOST);
        }

        List<PackageItem> allItems = packageItemRepository.findByVersionNameOrderByImageTagAsc(versionName);
        List<PackageItem> targets = resolveRetryTargets(allItems, request, workDirLost);
        if (targets.isEmpty()) {
            throw new ApiException(ErrorCode.NO_PACKAGING_TARGET, "재시도할 대상이 없습니다.");
        }

        for (PackageItem item : targets) {
            item.resetForRetry();
        }
        packageItemRepository.saveAll(targets);
        job.changeStatus(JobStatus.DOWNLOADING);

        List<PackageItem> items = packageItemRepository.findByVersionNameOrderByImageTagAsc(versionName);
        return PackageJobDetailResponse.builder()
                .job(PackageJobResponse.of(job, items))
                .items(items.stream().map(PackageItemResponse::from).toList())
                .build();
    }

    private List<PackageItem> resolveRetryTargets(List<PackageItem> allItems, PackageItemRetryRequest request, boolean workDirLost) {
        if (workDirLost) {
            // force=true로만 여기 온다(위에서 이미 걸렀다) — 전건 재수집.
            return allItems;
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
            throw new ApiException(
                    ErrorCode.MAIN_VERSION_NOT_FOUND, "메인버전 '%s'을(를) 찾을 수 없습니다.".formatted(versionName));
        }
    }

    private void assertTargetTagsValid(String versionName, List<String> targetTags) {
        Set<String> unique = new HashSet<>(targetTags);
        if (unique.size() != targetTags.size()) {
            throw new ApiException(ErrorCode.INVALID_IMAGE_TAG_SELECTION, "imageTags 목록에 중복된 태그가 있습니다.");
        }

        // 방어 심층화 — 등록 시점(SubVersionService.resolveImageTags)에 이미 문법을
        // 강제하지만, 그 검증이 생기기 전에 저장된 component 행이 남아 있을 수 있다.
        // 여기서 다시 걸러야 확정 시점부터라도 오염된 image_tag가 package_item으로
        // 스냅샷되지 않는다.
        for (String tag : targetTags) {
            try {
                ImageReference.parse(tag);
            } catch (IllegalArgumentException e) {
                throw new ApiException(ErrorCode.INVALID_IMAGE_TAG_SELECTION, e.getMessage());
            }
        }

        Set<String> validTags = componentRepository.findByMainVersionName(versionName).stream()
                .map(Component::getImageTag)
                .collect(Collectors.toSet());
        List<String> unknownTags = targetTags.stream().filter(tag -> !validTags.contains(tag)).toList();
        if (!unknownTags.isEmpty()) {
            List<String> shown = unknownTags.stream().limit(20).toList();
            throw new ApiException(
                    ErrorCode.INVALID_IMAGE_TAG_SELECTION,
                    "메인버전에 없는 image_tag가 포함되어 있습니다: %s".formatted(shown),
                    shown);
        }
    }

    /**
     * FN-11 중복 확인. 행이 없으면 신규 생성(동시 요청 시 유니크 제약 위반을 E-1301로
     * 번역). 행이 있으면 락을 잡고 상태로 재사용 가능 여부를 판정한다 — DONE(미삭제)은
     * force=true일 때만, FAILED와 이미 정리된 DONE은 항상, 그 외 진행 중 상태는 force로도
     * 재사용하지 않는다.
     *
     * <p>존재 확인은 반드시 {@code existsById}(count 쿼리)로 한다 — {@code findById}를
     * 쓰면 엔티티가 영속성 컨텍스트에 캐시되고, 바로 뒤 {@code findByVersionName}의
     * {@code FOR UPDATE}는 DB에서는 락을 잡지만 Hibernate가 1차 캐시 인스턴스를 그대로
     * 반환해 락 획득 전 시점의 stale 값을 보게 된다 — 그러면 두 동시 요청이 모두 같은
     * (오래된) 상태를 보고 통과해버려 락이 있으나 마나 해진다.
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
                    default -> true; // PENDING/VALIDATING/DOWNLOADING/UPLOADING — force로도 안 뚫림
                };
        if (blocked) {
            throw new ApiException(
                    ErrorCode.DUPLICATE_PACKAGE_JOB,
                    "메인버전 '%s'의 패키지 Job(%s)이 이미 있습니다.".formatted(versionName, existing.getStatus()),
                    List.of("status=" + existing.getStatus(), "spFolderUrl=" + existing.getSpFolderUrl()));
        }
        existing.resetForRerun(createdBy);
        return existing;
    }

    /**
     * 신규 INSERT 경합에서만 부른다. 원인이 실제 PK 유니크 제약 위반(MySQL 1062)일 때만
     * E-1301로 번역하고, 그 밖의 제약 위반(예: {@code createdBy} 길이 초과)은 원래
     * 예외를 그대로 던져 "다시 시도하세요"라는 오해를 주는 오류로 둔갑하지 않게 한다.
     */
    private RuntimeException translateJobInsertConflict(DataIntegrityViolationException e) {
        Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(e);
        if (rootCause instanceof SQLIntegrityConstraintViolationException sqlEx && sqlEx.getErrorCode() == 1062) {
            return new ApiException(ErrorCode.JOB_CREATION_CONFLICT);
        }
        return e;
    }

    /**
     * 확정을 막는 검사다(구현계획서 408행 "부족 시 경고 후 사용자 확인" — force=false면
     * 409로 차단, force=true는 이 확인을 거친 것으로 보고 진행한다). 실제 소요량 기준
     * 차단은 Phase 4-2가 매니페스트 layer 크기 합계로 별도 판정한다(E-0602) — 여기서는
     * 설정된 여유 공간 기준값과만 비교한다. 작업 디렉터리가 아직 없으면 만들어 보고,
     * 생성 자체가 실패하면(예: 권한 문제) 검사만 건너뛴다 — 그 실패로 매니페스트 확정
     * 전체가 막혀서는 안 된다.
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
        // 서버 파일시스템 경로·실시간 여유 용량은 응답에 싣지 않는다 — 인프라 정보라
        // 인증이 없는 이 API에도 노출 범위를 좁게 유지한다. 로그에만 남긴다.
        log.warn(
                "디스크 여유 공간 부족: workDir={}, usable={} bytes, threshold={} bytes, force={}",
                workDir,
                usableBytes,
                minFreeDiskBytes,
                force);
        if (!force) {
            throw new ApiException(
                    ErrorCode.INSUFFICIENT_DISK_SPACE,
                    "작업 디렉터리 여유 공간이 부족합니다. force=true로 진행할 수 있습니다.");
        }
    }
}
