package com.deployhub.common;

/**
 * 포맷을 통제하지 않는 텍스트(skopeo stderr 등)에서 알려진 자격 증명 문자열을 지운다.
 * REGISTRY_AUTH_FILE로 CLI 인자 노출은 막았지만 오류 메시지에 인증 파일 내용이 반영될 여지가 남아 있다.
 */
public final class CredentialMasker {

    private CredentialMasker() {}

    public static String mask(String text, String... secrets) {
        if (text == null) {
            return null;
        }
        String masked = text;
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                masked = masked.replace(secret, "****");
            }
        }
        return masked;
    }
}
