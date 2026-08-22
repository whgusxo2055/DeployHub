package com.deployhub.registry;

import java.util.regex.Pattern;

/**
 * {@code image_tag}를 {@code repository}/{@code tag}로 분리한다 — 마지막 콜론이 구분자다.
 * 이 값은 사용자 입력이 그대로 NCR REST 경로에 붙으므로, 여기서 Docker 문법을 강제해
 * 경로·쿼리 인젝션 문자(공백, {@code /../}, {@code ?}, {@code #})를 걸러낸다.
 */
public record ImageReference(String repository, String tag) {

    // 막아야 할 것은 공백·'..'·'?'·'#'뿐이다 — 형식 강제는 레지스트리 조회(E-0206)가 대신한다.
    // 대소문자는 가리지 않는다(distribution은 소문자만 받지만 그걸 여기서 재현할 이유가 없다).
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");
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
     * 치환이 단사가 아니라 서로 다른 태그가 같은 이름이 될 수 있다 — 그건 확정 시점에 거부한다
     * ({@code PackageJobService.assertTargetTagsValid}).
     */
    public String tarFileName() {
        return (repository + ":" + tag).replace('/', '_').replace(':', '_') + ".tar";
    }
}
