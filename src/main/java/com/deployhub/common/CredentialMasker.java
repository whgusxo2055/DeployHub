package com.deployhub.common;

/**
 * 외부 프로세스(skopeo) stderr 등 우리가 포맷을 통제하지 않는 텍스트에서 알려진 자격
 * 증명 문자열을 지운다. REGISTRY_AUTH_FILE 방식으로 전환해 CLI 인자 노출은 막았지만,
 * skopeo가 오류 메시지에 인증 파일 내용을 그대로 반영할 가능성까지 배제할 수 없어
 * 방어적으로 남겨둔다.
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
