package com.deployhub.version.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.deployhub.version.entity.Component;
import com.deployhub.version.entity.MainVersion;
import com.deployhub.version.entity.SubVersion;
import com.deployhub.version.repository.ComponentRepository;
import com.deployhub.version.repository.MainVersionRepository;
import com.deployhub.version.repository.SubVersionRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 구현계획서 Phase 1 완료 기준: "최초 메인버전(직전 없음 → 전건 변경)" 케이스와,
 * version이 같아도 태그 구성이 달라지면 컴포넌트만 변경으로 잡히는 케이스.
 * 둘 다 직전 메인버전 조회 결과에 좌우돼 순수 함수 단위로는 검증할 수 없다.
 */
@ExtendWith(MockitoExtension.class)
class VersionComparisonServiceTest {

    @Mock
    private MainVersionRepository mainVersionRepository;

    @Mock
    private SubVersionRepository subVersionRepository;

    @Mock
    private ComponentRepository componentRepository;

    @Test
    void 직전_메인버전이_없으면_모든_서브버전과_컴포넌트를_변경으로_판정한다() {
        VersionComparisonService service =
                new VersionComparisonService(mainVersionRepository, subVersionRepository, componentRepository);

        String versionName = "2026.08.05";
        SubVersion cc = newSubVersion(1L, versionName, "cc", "v2.0.25");
        SubVersion ocr = newSubVersion(2L, versionName, "ocr", "v0.9.15");

        when(subVersionRepository.findByMainVersionNameOrderBySortOrderAsc(versionName))
                .thenReturn(List.of(cc, ocr));
        when(mainVersionRepository.findPrevious(MainVersion.sortKeyOf(versionName))).thenReturn(Optional.empty());
        when(componentRepository.findBySubVersionIdInOrderBySortOrderAsc(any()))
                .thenReturn(List.of(
                        newComponent(1L, "sb-cc-api:v2.0.25.8612"), newComponent(2L, "ocr:v0.9.15")));

        Map<Long, SubVersionChange> changes = service.computeChanges(versionName);

        assertThat(changes.get(1L).changed()).isTrue();
        assertThat(changes.get(1L).componentChangedByImageTag()).containsEntry("sb-cc-api:v2.0.25.8612", true);
        assertThat(changes.get(2L).changed()).isTrue();
        assertThat(changes.get(2L).componentChangedByImageTag()).containsEntry("ocr:v0.9.15", true);
    }

    @Test
    void version이_같아도_태그가_바뀌면_컴포넌트만_변경으로_판정한다() {
        VersionComparisonService service =
                new VersionComparisonService(mainVersionRepository, subVersionRepository, componentRepository);

        String versionName = "2026.08.06";
        SubVersion current = newSubVersion(10L, versionName, "cc", "v2.0.25");
        SubVersion previous = newSubVersion(20L, "2026.08.05", "cc", "v2.0.25"); // version 동일

        when(subVersionRepository.findByMainVersionNameOrderBySortOrderAsc(versionName)).thenReturn(List.of(current));
        when(mainVersionRepository.findPrevious(MainVersion.sortKeyOf(versionName)))
                .thenReturn(Optional.of(MainVersion.builder().versionName("2026.08.05").build()));
        when(subVersionRepository.findByMainVersionNameOrderBySortOrderAsc("2026.08.05")).thenReturn(List.of(previous));
        when(componentRepository.findBySubVersionIdInOrderBySortOrderAsc(Set.of(20L)))
                .thenReturn(List.of(newComponent(20L, "sb-cc-api:v2.0.25.8612"), newComponent(20L, "ocr:v0.9.15")));
        when(componentRepository.findBySubVersionIdInOrderBySortOrderAsc(List.of(10L)))
                .thenReturn(List.of(newComponent(10L, "sb-cc-api:v2.0.25.9999"), newComponent(10L, "ocr:v0.9.15")));

        Map<Long, SubVersionChange> changes = service.computeChanges(versionName);

        assertThat(changes.get(10L).changed()).isFalse();
        assertThat(changes.get(10L).componentChangedByImageTag())
                .containsEntry("sb-cc-api:v2.0.25.9999", true)
                .containsEntry("ocr:v0.9.15", false);
    }

    private static SubVersion newSubVersion(Long id, String mainVersionName, String code, String version) {
        return SubVersion.builder()
                .id(id)
                .mainVersionName(mainVersionName)
                .code(code)
                .version(version)
                .sortOrder(0)
                .build();
    }

    private static Component newComponent(Long subVersionId, String imageTag) {
        return Component.builder().subVersionId(subVersionId).imageTag(imageTag).sortOrder(0).build();
    }
}
