package com.deployhub.version.service;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.common.PageResponse;
import com.deployhub.job.entity.PackageJob;
import com.deployhub.job.repository.PackageJobRepository;
import com.deployhub.version.dto.ComponentResponse;
import com.deployhub.version.dto.DetailSummaryResponse;
import com.deployhub.version.dto.JobSummaryResponse;
import com.deployhub.version.dto.MainVersionCreateRequest;
import com.deployhub.version.dto.MainVersionDetailResponse;
import com.deployhub.version.dto.MainVersionInfoResponse;
import com.deployhub.version.dto.MainVersionSummaryResponse;
import com.deployhub.version.dto.MainVersionUpdateRequest;
import com.deployhub.version.dto.PackagingEligibilityResponse;
import com.deployhub.version.dto.SubVersionResponse;
import com.deployhub.version.entity.Component;
import com.deployhub.version.entity.MainVersion;
import com.deployhub.version.entity.SubVersion;
import com.deployhub.version.repository.ComponentRepository;
import com.deployhub.version.repository.MainVersionRepository;
import com.deployhub.version.repository.SubVersionRepository;
import com.deployhub.version.repository.VersionCount;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 메인버전 목록·상세 조회와 등록·수정. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MainVersionService {

    private final MainVersionRepository mainVersionRepository;
    private final SubVersionRepository subVersionRepository;
    private final ComponentRepository componentRepository;
    private final PackageJobRepository packageJobRepository;
    private final VersionComparisonService versionComparisonService;
    private final PackagingEligibilityService packagingEligibilityService;

    @Transactional
    public MainVersionInfoResponse create(MainVersionCreateRequest request) {
        if (mainVersionRepository.existsById(request.versionName())) {
            throw new ApiException(
                    ErrorCode.MAIN_VERSION_ALREADY_EXISTS, List.of("versionName=" + request.versionName()));
        }
        MainVersion saved;
        try {
            saved = mainVersionRepository.saveAndFlush(MainVersion.builder()
                    .versionName(request.versionName())
                    .sortKey(MainVersion.sortKeyOf(request.versionName()))
                    .releaseNote(request.releaseNote())
                    .sqlScript(request.sqlScript())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // uk_main_version_sort_key. 등록 정규식이 정규형만 받으므로 정상 경로에서는 안 걸리고,
            // 그 정규식이 느슨하던 시절에 들어온 옛 행('2026.08.05.1' 등)과 겹칠 때만 남는다.
            throw new ApiException(
                    ErrorCode.MAIN_VERSION_ALREADY_EXISTS, List.of("versionName=" + request.versionName()));
        }
        copySubVersionsFromPrevious(saved.getVersionName());
        return MainVersionInfoResponse.from(saved);
    }

    /**
     * 직전 담당 영역을 전건 PENDING으로 복사한다 — 안 하면 등록자가 빠뜨린 모듈이 아무 데도 안 걸린다.
     * note는 제외(직전 배포의 변경 사항이 릴리즈 노트에 실린다). 레지스트리 재확인·락 없음.
     */
    private void copySubVersionsFromPrevious(String versionName) {
        String previous = versionComparisonService
                .findPreviousMainVersion(versionName)
                .map(MainVersion::getVersionName)
                .orElse(null);
        if (previous == null) {
            return; // 최초 메인버전 — 등록자가 직접 채운다.
        }

        List<SubVersion> sources = subVersionRepository.findByMainVersionNameOrderBySortOrderAsc(previous);
        Map<Long, List<Component>> componentsBySourceId = componentRepository
                .findBySubVersionIdInOrderBySortOrderAsc(sources.stream().map(SubVersion::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(Component::getSubVersionId));

        for (SubVersion source : sources) {
            SubVersion copy = subVersionRepository.save(SubVersion.builder()
                    .mainVersionName(versionName)
                    .code(source.getCode())
                    .version(source.getVersion())
                    .sortOrder(source.getSortOrder())
                    .build());
            for (Component component : componentsBySourceId.getOrDefault(source.getId(), List.of())) {
                componentRepository.save(Component.builder()
                        .subVersionId(copy.getId())
                        .imageTag(component.getImageTag())
                        .sortOrder(component.getSortOrder())
                        .build());
            }
        }
        log.info("직전 담당 영역 복사: versionName={}, previous={}, subVersions={}", versionName, previous, sources.size());
    }

    @Transactional
    public MainVersionInfoResponse update(String versionName, MainVersionUpdateRequest request) {
        MainVersion mainVersion = getOrThrow(versionName);
        mainVersion.update(request.releaseNote(), request.sqlScript());
        return MainVersionInfoResponse.from(mainVersion);
    }

    public PageResponse<MainVersionSummaryResponse> list(String keyword, Pageable pageable) {
        String normalizedKeyword = StringUtils.hasText(keyword) ? keyword : null;
        Page<MainVersion> page = mainVersionRepository.search(normalizedKeyword, pageable);

        List<MainVersionSummaryResponse> items = toSummaries(page.getContent());
        return PageResponse.<MainVersionSummaryResponse>builder()
                .items(items)
                .totalCount(page.getTotalElements())
                .page(page.getNumber())
                .size(page.getSize())
                .build();
    }

    public MainVersionDetailResponse getDetail(String versionName) {
        MainVersion mainVersion = getOrThrow(versionName);

        List<SubVersion> subVersions = subVersionRepository.findByMainVersionNameOrderBySortOrderAsc(versionName);
        List<Long> subVersionIds = subVersions.stream().map(SubVersion::getId).toList();
        Map<Long, List<Component>> componentsBySubVersionId = componentRepository
                .findBySubVersionIdInOrderBySortOrderAsc(subVersionIds)
                .stream()
                .collect(Collectors.groupingBy(Component::getSubVersionId));

        Map<Long, SubVersionChange> changes = versionComparisonService.computeChanges(versionName);

        List<SubVersionResponse> subVersionResponses = subVersions.stream()
                .map(subVersion -> toSubVersionResponse(
                        subVersion,
                        componentsBySubVersionId.getOrDefault(subVersion.getId(), List.of()),
                        changes.get(subVersion.getId())))
                .toList();

        int componentCount = componentsBySubVersionId.values().stream().mapToInt(List::size).sum();
        return MainVersionDetailResponse.builder()
                .mainVersion(MainVersionInfoResponse.from(mainVersion))
                .subVersions(subVersionResponses)
                .summary(DetailSummaryResponse.builder()
                        .subVersionCount(subVersions.size())
                        .componentCount(componentCount)
                        .build())
                .build();
    }

    public PackagingEligibilityResponse getPackagingEligibility(String versionName) {
        getOrThrow(versionName);
        PackagingEligibility eligibility = packagingEligibilityService.evaluate(versionName);
        return PackagingEligibilityResponse.builder()
                .eligible(eligibility.eligible())
                .blockingSubVersionCodes(eligibility.blockingSubVersionCodes())
                .build();
    }

    private MainVersion getOrThrow(String versionName) {
        return mainVersionRepository
                .findById(versionName)
                .orElseThrow(() ->
                        new ApiException(ErrorCode.MAIN_VERSION_NOT_FOUND, List.of("versionName=" + versionName)));
    }

    /**
     * 항목마다 카운트 2번 + Job 1번을 치면 page size에 비례해 쿼리가 는다(무인증 API라 size를
     * 크게 부르는 것도 막을 수 없다) — 집계 2번 + Job 1번으로 고정한다.
     */
    private List<MainVersionSummaryResponse> toSummaries(List<MainVersion> mainVersions) {
        List<String> versionNames = mainVersions.stream().map(MainVersion::getVersionName).toList();
        if (versionNames.isEmpty()) {
            return List.of();
        }
        Map<String, Long> subVersionCounts = toCountMap(subVersionRepository.countByMainVersionNames(versionNames));
        Map<String, Long> componentCounts = toCountMap(componentRepository.countByMainVersionNames(versionNames));
        Map<String, JobSummaryResponse> lastJobs = packageJobRepository.findAllById(versionNames).stream()
                .collect(Collectors.toMap(PackageJob::getVersionName, JobSummaryResponse::from));

        return mainVersions.stream()
                .map(mainVersion -> MainVersionSummaryResponse.builder()
                        .versionName(mainVersion.getVersionName())
                        .subVersionCount(subVersionCounts.getOrDefault(mainVersion.getVersionName(), 0L).intValue())
                        .componentCount(componentCounts.getOrDefault(mainVersion.getVersionName(), 0L).intValue())
                        .lastJob(lastJobs.get(mainVersion.getVersionName()))
                        .build())
                .toList();
    }

    private static Map<String, Long> toCountMap(List<VersionCount> counts) {
        return counts.stream().collect(Collectors.toMap(VersionCount::versionName, VersionCount::count));
    }

    private SubVersionResponse toSubVersionResponse(
            SubVersion subVersion, List<Component> components, SubVersionChange change) {
        boolean subVersionChanged = change != null && change.changed();
        Map<String, Boolean> componentChanges = change == null ? Map.of() : change.componentChangedByImageTag();

        List<ComponentResponse> componentResponses = components.stream()
                .map(component -> ComponentResponse.builder()
                        .imageTag(component.getImageTag())
                        .sortOrder(component.getSortOrder())
                        .changed(componentChanges.getOrDefault(component.getImageTag(), true))
                        .build())
                .toList();

        return SubVersionResponse.builder()
                .id(subVersion.getId())
                .code(subVersion.getCode())
                .version(subVersion.getVersion())
                .note(subVersion.getNote())
                .sortOrder(subVersion.getSortOrder())
                .submitStatus(subVersion.getSubmitStatus().name())
                .submittedAt(subVersion.getSubmittedAt())
                .changed(subVersionChanged)
                .components(componentResponses)
                .build();
    }
}
