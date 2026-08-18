package com.deployhub.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * {@code image_tag}를 {@code repository}/{@code tag}로 분리한다 — 마지막 콜론이 구분자다.
 * 이 값은 사용자 입력이 그대로 NCR REST 경로에 붙으므로, 여기서 Docker 문법을 강제해
 * 경로·쿼리 인젝션 문자(공백, {@code /../}, {@code ?}, {@code #})를 걸러낸다.
 */
public record ImageReference(String repository, String tag) {

    // 이 값이 NCR REST 경로에 그대로 붙는다 — 막아야 할 것은 공백·'..'·'?'·'#'이지
    // distribution 문법의 정확한 재현이 아니다. 영숫자로 시작하고 그 뒤로 [a-z0-9._/-]만 허용하면
    // 경로·쿼리 조작 문자가 전부 걸리고, foo__bar·a--b 같은 유효한 이름도 그대로 통과한다.
    // '..'만 따로 막는다 — 위 문자 집합으로는 걸리지 않는데 경로를 거슬러 올라간다.
    private static final Pattern REPOSITORY = Pattern.compile("[a-z0-9][a-z0-9._/-]*");
    private static final Pattern TAG = Pattern.compile("[\\w][\\w.-]{0,127}");

    public static ImageReference parse(String imageTag) {
        int lastColon = imageTag.lastIndexOf(':');
        if (lastColon <= 0 || lastColon == imageTag.length() - 1) {
            throw new IllegalArgumentException("image_tag 형식이 올바르지 않습니다: " + imageTag);
        }
        String repository = imageTag.substring(0, lastColon);
        String tag = imageTag.substring(lastColon + 1);
        if (!REPOSITORY.matcher(repository).matches() || repository.contains("..") || !TAG.matcher(tag).matches()) {
            throw new IllegalArgumentException("image_tag 형식이 올바르지 않습니다: " + imageTag);
        }
        return new ImageReference(repository, tag);
    }

    /**
     * 다운로드가 만든 tar 파일명을 업로드가 다시 계산해 찾으므로 두 곳이 이 규칙을 공유해야 한다.
     * '/'·':' 치환만으로는 단사가 아니라({@code a/b:1}과 {@code a_b:1}이 충돌) 해시 접미사를 붙인다.
     */
    public String tarFileName() {
        String identity = repository + ":" + tag;
        String base = identity.replace('/', '_').replace(':', '_');
        return base + "_" + sha256Hex8(identity) + ".tar";
    }

    private static String sha256Hex8(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
