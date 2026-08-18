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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 구현계획서 Phase 1 완료 기준: "최초 메인버전(직전 없음 → 전건 변경)" 케이스.
 * 이 판단은 리포지토리 조회 결과(직전 메인버전 자체의 부재)에 좌우되므로
 * {@link ChangeDetector} 단독 테스트로는 검증할 수 없어 서비스 수준에서 확인한다.
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
        when(componentRepository.findBySubVersionIdIn(any()))
                .thenReturn(List.of(
                        newComponent(1L, "sb-cc-api:v2.0.25.8612"), newComponent(2L, "ocr:v0.9.15")));

        Map<Long, SubVersionChange> changes = service.computeChanges(versionName);

        assertThat(changes.get(1L).changed()).isTrue();
        assertThat(changes.get(1L).componentChangedByImageTag()).containsEntry("sb-cc-api:v2.0.25.8612", true);
        assertThat(changes.get(2L).changed()).isTrue();
        assertThat(changes.get(2L).componentChangedByImageTag()).containsEntry("ocr:v0.9.15", true);
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
