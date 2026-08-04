package com.deployhub.version.service;

import java.util.Set;

/**
 * 직전 메인버전 대비 변경 여부 판정의 핵심 규칙 (구현계획서 Phase 1-4).
 * 이 로직이 Phase 3의 패키징 대상(변경된 컴포넌트만 적재)을 결정하므로 DB 접근과
 * 분리된 순수 함수로 둔다.
 */
public final class ChangeDetector {

    private ChangeDetector() {}

    /**
     * 서브버전 단위 변경 여부. 직전 메인버전에 동일 code의 서브버전이 없으면(previousVersion == null)
     * 신규 등록이거나 최초 메인버전이라는 뜻이므로 변경으로 판정한다.
     */
    public static boolean isSubVersionChanged(String currentVersion, String previousVersion) {
        if (previousVersion == null) {
            return true;
        }
        return !currentVersion.equals(previousVersion);
    }

    /**
     * 컴포넌트(이미지 태그) 단위 변경 여부. 서브버전의 version 문자열이 같더라도 이미지 태그
     * 구성이 달라졌으면 변경으로 판정해야 하므로 {@link #isSubVersionChanged}와 독립적으로 계산한다.
     */
    public static boolean isComponentChanged(String imageTag, Set<String> previousImageTags) {
        return previousImageTags == null || !previousImageTags.contains(imageTag);
    }
}
