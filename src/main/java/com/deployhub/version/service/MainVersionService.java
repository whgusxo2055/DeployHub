package com.deployhub.version.service;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.common.PageResponse;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 메인버전 목록·상세 조회와 등록·수정. */
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
                    ErrorCode.MAIN_VERSION_ALREADY_EXISTS,
                    "메인버전 '%s'은(는) 이미 등록되어 있습니다.".formatted(request.versionName()));
        }
        MainVersion saved = mainVersionRepository.save(MainVersion.builder()
                .versionName(request.versionName())
                .releaseNote(request.releaseNote())
                .sqlScript(request.sqlScript())
                .build());
        return MainVersionInfoResponse.from(saved);
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

        List<MainVersionSummaryResponse> items = page.getContent().stream().map(this::toSummary).toList();
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
                .findBySubVersionIdIn(subVersionIds)
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
        int missingCount = (int) subVersions.stream()
                .filter(subVersion -> componentsBySubVersionId.getOrDefault(subVersion.getId(), List.of()).isEmpty())
                .count();

        return MainVersionDetailResponse.builder()
                .mainVersion(MainVersionInfoResponse.from(mainVersion))
                .subVersions(subVersionResponses)
                .summary(DetailSummaryResponse.builder()
                        .subVersionCount(subVersions.size())
                        .componentCount(componentCount)
                        .missingCount(missingCount)
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
                .orElseThrow(() -> new ApiException(
                        ErrorCode.MAIN_VERSION_NOT_FOUND, "메인버전 '%s'을(를) 찾을 수 없습니다.".formatted(versionName)));
    }

    private MainVersionSummaryResponse toSummary(MainVersion mainVersion) {
        String versionName = mainVersion.getVersionName();
        long subVersionCount = subVersionRepository.countByMainVersionName(versionName);
        long componentCount = componentRepository.countByMainVersionName(versionName);
        JobSummaryResponse lastJob =
                packageJobRepository.findById(versionName).map(JobSummaryResponse::from).orElse(null);
        return MainVersionSummaryResponse.builder()
                .versionName(versionName)
                .subVersionCount((int) subVersionCount)
                .componentCount((int) componentCount)
                .lastJob(lastJob)
                .build();
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
                .submittedBy(subVersion.getSubmittedBy())
                .submittedAt(subVersion.getSubmittedAt())
                .changed(subVersionChanged)
                .components(componentResponses)
                .build();
    }
}
