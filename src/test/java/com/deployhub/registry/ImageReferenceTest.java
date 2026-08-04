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
}
