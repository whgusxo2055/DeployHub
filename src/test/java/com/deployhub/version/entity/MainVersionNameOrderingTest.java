package com.deployhub.version.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.deployhub.version.dto.MainVersionCreateRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 정렬 전용 컬럼을 없앤 근거 — index가 3자리로 고정이면 {@code version_name} 문자열 비교가 곧
 * 배포 순서다. 자리수가 섞이면 그 전제가 깨지므로(예: '...010' &lt; '...2') 등록 정규식이 유일한
 * 방어선이다. DB 정렬(utf8mb4_0900_ai_ci)도 같은 순서임을 서버에서 확인했다(2026-08-24).
 */
class MainVersionNameOrderingTest {

    @Test
    void 이름_문자열_정렬이_곧_배포_순서다() {
        List<String> shuffled =
                List.of("2026.08.24.010", "2026.08.25.001", "2026.08.24.002", "2026.09.01.001", "2026.08.24.100");

        assertThat(shuffled.stream().sorted().toList())
                .containsExactly(
                        "2026.08.24.002", "2026.08.24.010", "2026.08.24.100", "2026.08.25.001", "2026.09.01.001");
    }

    /** 패딩이 없거나 섞이면 뒤집힌다 — 정규식이 이걸 막고 있다는 사실을 고정한다. */
    @Test
    void 자리수가_섞이면_정렬이_뒤집힌다() {
        assertThat(List.of("2026.08.24.2", "2026.08.24.010").stream().sorted().toList())
                .containsExactly("2026.08.24.010", "2026.08.24.2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026.08.24.001", "2026.08.24.010", "2026.08.24.999"})
    void 정규형_이름은_통과한다(String versionName) {
        assertThat(violations(versionName)).isEmpty();
    }

    /** 000은 001과 의미가 겹치고, 나머지는 자리수가 어긋나 정렬 전제를 깬다. */
    @ParameterizedTest
    @ValueSource(strings = {"2026.08.24", "2026.08.24.1", "2026.08.24.01", "2026.08.24.000", "2026.08.24-001",
            "2026.08.24.0001"})
    void 자리수가_어긋난_이름은_등록_정규식이_거부한다(String versionName) {
        assertThat(violations(versionName)).isNotEmpty();
    }

    private static Set<ConstraintViolation<MainVersionCreateRequest>> violations(String versionName) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(new MainVersionCreateRequest(versionName, null, null));
        }
    }
}
