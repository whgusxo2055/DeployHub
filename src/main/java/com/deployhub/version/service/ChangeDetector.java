package com.deployhub.version.service;

import java.util.Set;

/** 직전 메인버전 대비 변경 여부 판정. 패키징 대상을 결정하는 규칙이라 DB 접근과 분리된 순수 함수로 둔다. */
public final class ChangeDetector {

    private ChangeDetector() {}

    /** 직전에 같은 code가 없으면(신규 등록이거나 최초 메인버전) 변경으로 판정한다. */
    public static boolean isSubVersionChanged(String currentVersion, String previousVersion) {
        if (previousVersion == null) {
            return true;
        }
        return !currentVersion.equals(previousVersion);
    }

    /** version 문자열이 같아도 태그 구성이 달라졌으면 변경이므로 {@link #isSubVersionChanged}와 독립 계산한다. */
    public static boolean isComponentChanged(String imageTag, Set<String> previousImageTags) {
        return previousImageTags == null || !previousImageTags.contains(imageTag);
    }
}
