package com.deployhub.common;

/**
 * baseUrl이 고정된 {@code RestClient}에 넘기는 경로가 host를 바꿀 수 없도록 막는다.
 * 상대 경로는 baseUrl의 host를 못 바꾸지만, 절대 URL을 실수로(혹은 나중에 사용자 입력이
 * 섞여) 넘기면 {@code DefaultUriBuilderFactory}가 baseUrl을 무시하고 그 host로 인증
 * 헤더를 그대로 보낸다 — NCR/Graph 클라이언트 둘 다 같은 위험이 있어 공용으로 뺐다.
 */
public final class RelativePathGuard {

    private RelativePathGuard() {}

    public static void requireRelative(String path) {
        if (!path.startsWith("/") || path.contains("://")) {
            throw new IllegalArgumentException("절대 URL은 이 경로 인자로 넘길 수 없습니다: " + path);
        }
    }
}
