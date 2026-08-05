package com.deployhub.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ImageReferenceTest {

    @Test
    void 마지막_콜론을_기준으로_repository와_tag를_분리한다() {
        ImageReference ref = ImageReference.parse("sb-cc-api:v2.0.25.8612");

        assertThat(ref.repository()).isEqualTo("sb-cc-api");
        assertThat(ref.tag()).isEqualTo("v2.0.25.8612");
    }

    @Test
    void 콜론이_없으면_예외를_던진다() {
        assertThatThrownBy(() -> ImageReference.parse("sb-cc-api")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tarFileName은_슬래시와_콜론이_뒤섞여도_충돌하지_않는다() {
        // "a/b:1"과 "a_b:1"은 '/'·':' 치환만으로는 같은 파일명("a_b_1")이 된다 — 해시
        // 접미사가 그 충돌을 없애는지 검증한다(코드리뷰로 발견된 버그의 회귀 방지).
        String nameWithSlash = ImageReference.parse("a/b:1").tarFileName();
        String nameWithUnderscore = ImageReference.parse("a_b:1").tarFileName();

        assertThat(nameWithSlash).isNotEqualTo(nameWithUnderscore);
    }
}
