package com.deployhub.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 노출 경계를 타입 대신 이 테스트가 지킨다 — enum을 하나로 합치면서 컴파일러가 막아주던
 * "로그 전용 코드가 응답에 실리는 것"을 여기와 두 개의 런타임 가드로 대체했다.
 */
class ErrorCodeExposureTest {

    /** 서버 경로·호스트·URL로 보이는 것. details는 값이 들어가는 자리라 대상이 아니다. */
    private static final Pattern LEAKY = Pattern.compile("(/|\\\\\\\\|://|\\b\\d{1,3}(\\.\\d{1,3}){3}\\b)");

    @Test
    void 노출되는_코드의_문구에는_경로나_호스트가_없다() {
        List<ErrorCode> leaky = Arrays.stream(ErrorCode.values())
                .filter(code -> code.getExposure() == ErrorCode.Exposure.PUBLIC)
                .filter(code -> LEAKY.matcher(code.getMessage()).find())
                .toList();

        assertThat(leaky)
                .as("이 문구는 무인증 응답에 그대로 실린다 — 경로·호스트를 넣지 말 것")
                .isEmpty();
    }

    @Test
    void HTTP_상태가_있는_코드는_반드시_노출_대상이다() {
        assertThat(Arrays.stream(ErrorCode.values())
                        .filter(code -> code.getHttpStatus() != null)
                        .filter(code -> code.getExposure() != ErrorCode.Exposure.PUBLIC)
                        .toList())
                .isEmpty();
    }

    @Test
    void 로그_전용_코드는_HTTP_응답이_될_수_없다() {
        assertThatThrownBy(() -> new ApiException(ErrorCode.UPLOAD_SESSION_GONE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
