package com.deployhub.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    /**
     * 이 클래스는 "경로·쿼리 인젝션 문자를 걸러낸다"는 보안 경계다 — 여기 통과한 값이 그대로
     * NCR REST 경로와 skopeo 인자로 들어간다. 정규식이 느슨해지면 이 목록이 먼저 깨진다.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "acme/../../v2/other:1", // 경로 탈출
                "acme/x:1?y=1", // 쿼리 인젝션
                "acme/x:1#frag", // 프래그먼트
                "acme/x:{tpl}", // Spring URI 템플릿 변수
                "ACME/X:1", // 대문자(distribution은 소문자만)
                "acme x:1", // 공백
                "acme/x:", // 빈 태그
                ":1", // 빈 저장소
                "acme/x:1\n2", // 개행
                "registry.example.com:5000/x:1" // 호스트 지정
            })
    void 경로_주입이_되는_문자열은_거절한다(String imageTag) {
        assertThatThrownBy(() -> ImageReference.parse(imageTag)).isInstanceOf(IllegalArgumentException.class);
    }

    /** distribution 문법이 허용하는 구분자 — 좁게 잡으면 유효한 저장소명이 등록에서 막힌다. */
    @ParameterizedTest
    @ValueSource(strings = {"foo__bar:1", "a--b:1", "acme/sub-ns/x:1.0", "a.b_c:v1"})
    void distribution_문법의_구분자를_허용한다(String imageTag) {
        assertThat(ImageReference.parse(imageTag).tag()).isNotBlank();
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
