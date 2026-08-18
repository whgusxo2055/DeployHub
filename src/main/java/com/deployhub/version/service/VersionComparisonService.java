package com.deployhub.version.service;

import com.deployhub.version.entity.Component;
import com.deployhub.version.entity.MainVersion;
import com.deployhub.version.entity.SubVersion;
import com.deployhub.version.repository.ComponentRepository;
import com.deployhub.version.repository.MainVersionRepository;
import com.deployhub.version.repository.SubVersionRepository;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "직전 버전 대비 변경 여부"를 조회 응답과 패키징 대상 선정이 함께 쓴다.
 * 표시용이 아니라 핵심 로직이므로 이 클래스에서만 계산한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VersionComparisonService {

    private final MainVersionRepository mainVersionRepository;
    private final SubVersionRepository subVersionRepository;
    private final ComponentRepository componentRepository;

    /** "직전 메인버전"은 정렬키가 대상보다 작은 것 중, 서브버전이 있는 최대값이다. */
    public Optional<MainVersion> findPreviousMainVersion(String versionName) {
        return mainVersionRepository.findPrevious(MainVersion.sortKeyOf(versionName));
    }

    /** 메인버전에 속한 서브버전·컴포넌트 전체의 변경 여부를 계산한다. subVersionId로 결과를 찾는다. */
    public Map<Long, SubVersionChange> computeChanges(String versionName) {
        List<SubVersion> currentSubVersions = subVersionRepository.findByMainVersionNameOrderBySortOrderAsc(versionName);
        if (currentSubVersions.isEmpty()) {
            return Map.of();
        }

        Optional<String> previousVersionName = findPreviousMainVersion(versionName).map(MainVersion::getVersionName);

        Map<String, SubVersion> previousSubVersionsByCode = previousVersionName
                .map(subVersionRepository::findByMainVersionNameOrderBySortOrderAsc)
                .orElse(List.of())
                .stream()
                .collect(Collectors.toMap(SubVersion::getCode, Function.identity()));

        // 서브버전마다 조회하면 직전 버전 컴포넌트가 N+1이 된다 — 아래 현재 버전과 같은
        // findBySubVersionIdIn으로 한 번에 가져온다.
        Map<Long, String> previousCodeBySubVersionId = previousSubVersionsByCode.values().stream()
                .collect(Collectors.toMap(SubVersion::getId, SubVersion::getCode));
        Map<String, Set<String>> previousImageTagsByCode = new HashMap<>();
        previousSubVersionsByCode.keySet().forEach(code -> previousImageTagsByCode.put(code, new HashSet<>()));
        if (!previousCodeBySubVersionId.isEmpty()) {
            for (Component component : componentRepository.findBySubVersionIdIn(previousCodeBySubVersionId.keySet())) {
                previousImageTagsByCode
                        .get(previousCodeBySubVersionId.get(component.getSubVersionId()))
                        .add(component.getImageTag());
            }
        }

        List<Long> currentSubVersionIds = currentSubVersions.stream().map(SubVersion::getId).toList();
        Map<Long, List<Component>> currentComponentsBySubVersionId = componentRepository
                .findBySubVersionIdIn(currentSubVersionIds)
                .stream()
                .collect(Collectors.groupingBy(Component::getSubVersionId));

        Map<Long, SubVersionChange> result = new LinkedHashMap<>();
        for (SubVersion subVersion : currentSubVersions) {
            SubVersion previousSubVersion = previousSubVersionsByCode.get(subVersion.getCode());
            boolean subVersionChanged = ChangeDetector.isSubVersionChanged(
                    subVersion.getVersion(), previousSubVersion == null ? null : previousSubVersion.getVersion());

            Set<String> previousTags = previousImageTagsByCode.getOrDefault(subVersion.getCode(), Set.of());
            List<Component> components = currentComponentsBySubVersionId.getOrDefault(subVersion.getId(), List.of());

            Map<String, Boolean> componentChanges = new LinkedHashMap<>();
            for (Component component : components) {
                componentChanges.put(
                        component.getImageTag(), ChangeDetector.isComponentChanged(component.getImageTag(), previousTags));
            }
            result.put(subVersion.getId(), new SubVersionChange(subVersionChanged, componentChanges));
        }
        return result;
    }

    /** 직전 메인버전 대비 변경된 image_tag 목록 — 패키징 대상의 기본 선택값이다. */
    public List<String> changedImageTags(String versionName) {
        return computeChanges(versionName).values().stream()
                .flatMap(change -> change.componentChangedByImageTag().entrySet().stream())
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .toList();
    }
}
