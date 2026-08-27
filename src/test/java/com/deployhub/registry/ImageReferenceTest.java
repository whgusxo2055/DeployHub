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
                "acme x:1", // 공백
                "acme/x:", // 빈 태그
                ":1", // 빈 저장소
                "acme/x:1\n2", // 개행
                "registry.example.com:5000/x:1" // 호스트 지정
            })
    void 경로_주입이_되는_문자열은_거절한다(String imageTag) {
        assertThatThrownBy(() -> ImageReference.parse(imageTag)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 형식 강제는 레지스트리 조회(E-0206)가 대신한다 — 여기서 좁게 잡으면 유효한 이름이 등록에서 막힌다. */
    @ParameterizedTest
    @ValueSource(strings = {"foo__bar:1", "a--b:1", "acme/sub-ns/x:1.0", "a.b_c:v1", "ACME/X:1"})
    void distribution_문법의_구분자를_허용한다(String imageTag) {
        assertThat(ImageReference.parse(imageTag).tag()).isNotBlank();
    }

    @Test
    void tarFileName은_슬래시와_콜론을_언더스코어로_치환한다() {
        // 구현계획서 FN-06-1의 규격 그대로다 — 접미사를 붙이지 않는다.
        assertThat(ImageReference.parse("acme/sb-cc-api:v2.0.25.8612").tarFileName())
                .isEqualTo("acme_sb-cc-api_v2.0.25.8612.tar");
    }

    @Test
    void tarFileName은_단사가_아니다() {
        // 이 충돌을 파일명으로 피하지 않고 확정 시점에 거부한다 — 거부 동작은
        // PackageJobApiFlowIntegrationTest.파일명이_겹치는_태그_조합은_E_0301로_거부된다가 지킨다.
        assertThat(ImageReference.parse("a/b:1").tarFileName())
                .isEqualTo(ImageReference.parse("a_b:1").tarFileName());
    }
}
