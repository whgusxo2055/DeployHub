package com.deployhub.version.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

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

    /** 구분자를 '.'과 '-' 둘 다 허용하는데 '-'(0x2D) &lt; '.'(0x2E)라 섞이면 순서가 또 갈린다. */
    @Test
    void 구분자가_섞여도_같은_index면_같은_정렬키다() {
        assertThat(MainVersion.sortKeyOf("2026.08.05-1")).isEqualTo(MainVersion.sortKeyOf("2026.08.05.1"));
    }

    @Test
    void index가_없으면_0으로_채운다() {
        assertThat(MainVersion.sortKeyOf("2026.08.05")).isEqualTo("2026.08.05.000");
    }
}
