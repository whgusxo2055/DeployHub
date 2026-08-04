package com.deployhub.version.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 구현계획서 Phase 1 완료 기준의 변경 여부 계산 5케이스 중, DB 조회 없이 순수하게
 * 검증 가능한 4건을 다룬다. "최초 메인버전(직전 없음)" 케이스는
 * {@link VersionComparisonServiceTest}에서 리포지토리 오케스트레이션 수준으로 검증한다.
 */
class ChangeDetectorTest {

    @Test
    void 신규_모듈은_직전_버전에_동일_code가_없으므로_변경으로_판정한다() {
        boolean changed = ChangeDetector.isSubVersionChanged("v1.0.0", null);

        assertThat(changed).isTrue();
    }

    @Test
    void 버전_문자열이_다르면_변경으로_판정한다() {
        boolean changed = ChangeDetector.isSubVersionChanged("v2.0.25", "v2.0.24");

        assertThat(changed).isTrue();
    }

    @Test
    void 버전_문자열이_같으면_변경_없음으로_판정한다() {
        boolean changed = ChangeDetector.isSubVersionChanged("v2.0.25", "v2.0.25");

        assertThat(changed).isFalse();
    }

    @Test
    void 서브버전_버전은_동일해도_컴포넌트_image_tag가_다르면_컴포넌트는_변경으로_판정한다() {
        // 서브버전 단위: version 문자열이 같으므로 변경 없음
        boolean subVersionChanged = ChangeDetector.isSubVersionChanged("v2.0.25", "v2.0.25");
        assertThat(subVersionChanged).isFalse();

        // 컴포넌트 단위: 직전 버전의 image_tag 집합에 새 태그가 없으면 독립적으로 변경 판정한다
        Set<String> previousImageTags = Set.of("sb-cc-api:v2.0.24.8507");
        boolean componentChanged = ChangeDetector.isComponentChanged("sb-cc-api:v2.0.25.8612", previousImageTags);

        assertThat(componentChanged).isTrue();
    }

    @Test
    void 컴포넌트_image_tag가_직전_집합에_있으면_변경_없음으로_판정한다() {
        Set<String> previousImageTags = Set.of("sb-cc-api:v2.0.25.8612");

        boolean changed = ChangeDetector.isComponentChanged("sb-cc-api:v2.0.25.8612", previousImageTags);

        assertThat(changed).isFalse();
    }

    @Test
    void 직전_컴포넌트_집합이_없으면_변경으로_판정한다() {
        boolean changed = ChangeDetector.isComponentChanged("sb-cc-api:v2.0.25.8612", null);

        assertThat(changed).isTrue();
    }
}
