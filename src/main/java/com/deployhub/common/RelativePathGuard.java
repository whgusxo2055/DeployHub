package com.deployhub.common;

/**
 * baseUrl이 고정된 {@code RestClient}에 절대 URL이 넘어가는 것을 막는다 —
 * {@code DefaultUriBuilderFactory}가 baseUrl을 무시하고 그 host로 인증 헤더를 그대로 보낸다.
 */
public final class RelativePathGuard {

    private RelativePathGuard() {}

    public static void requireRelative(String path) {
        // "//host/x"는 authority로 파싱돼 baseUrl을 벗어난다 — "/"로 시작한다고 안전한 게 아니다.
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("://")) {
            throw new IllegalArgumentException("절대 URL은 이 경로 인자로 넘길 수 없습니다: " + path);
        }
    }
}
