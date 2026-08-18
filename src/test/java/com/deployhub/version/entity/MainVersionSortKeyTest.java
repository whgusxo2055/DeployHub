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
 * "직전 메인버전"은 정렬키의 문자열 비교로 찾는다 — 그 비교가 날짜·index 순서와 일치하는지 본다.
 *
 * <p>{@code version_name}을 그대로 비교하면 index가 두 자리가 되는 순간 뒤집힌다
 * ({@code '2026.08.05-10' < '2026.08.05-2'}). 그러면 직전이 -9가 아니라 -1로 잡혀 그 사이
 * 버전들의 컴포넌트가 통째로 "변경됨"으로 되살아난다 — 표시 오류가 아니라 패키징 대상 오판이다.
 */
class MainVersionSortKeyTest {

    @Test
    void index가_두_자리가_되어도_정렬이_뒤집히지_않는다() {
        List<String> versionNames = List.of("2026.08.05", "2026.08.05-2", "2026.08.05-9", "2026.08.05-10");

        List<String> sorted = versionNames.stream().sorted().toList();
        List<String> sortedByKey = versionNames.stream()
                .sorted((a, b) -> MainVersion.sortKeyOf(a).compareTo(MainVersion.sortKeyOf(b)))
                .toList();

        // version_name 직접 비교는 -10을 -2보다 앞에 둔다(= 이 기능의 원래 버그).
        assertThat(sorted).containsExactly("2026.08.05", "2026.08.05-10", "2026.08.05-2", "2026.08.05-9");
        assertThat(sortedByKey).containsExactly("2026.08.05", "2026.08.05-2", "2026.08.05-9", "2026.08.05-10");
    }

    /**
     * 정렬키는 구분자를 통일하고 index를 0으로 채우므로 서로 다른 version_name이 같은 정렬키가 될 수
     * 있다. sort_key에는 UNIQUE 인덱스(V3)가 있어, 이런 별칭이 등록까지 오면 existsById 검사를 지나
     * DB 제약 위반(500)으로 터진다 — 등록 정규식이 애초에 막는지 본다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"2026.08.05.1", "2026.08.05-01", "2026.08.05-001", "2026.08.05-0", "2026.08.05-0001"})
    void 정렬키가_겹치는_별칭_이름은_등록_정규식이_거부한다(String alias) {
        assertThat(MainVersion.sortKeyOf(alias)).isIn("2026.08.05.001", "2026.08.05.000");
        assertThat(violations(alias)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026.08.05", "2026.08.05-1", "2026.08.05-10", "2026.08.05-999"})
    void 정규형_이름은_통과한다(String versionName) {
        assertThat(violations(versionName)).isEmpty();
    }

    private static Set<ConstraintViolation<MainVersionCreateRequest>> violations(String versionName) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(new MainVersionCreateRequest(versionName, null, null));
        }
    }

    @Test
    void index가_없으면_0으로_채운다() {
        assertThat(MainVersion.sortKeyOf("2026.08.05")).isEqualTo("2026.08.05.000");
    }
}
