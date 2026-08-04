package com.deployhub.registry;

import java.util.regex.Pattern;

/**
 * {@code image_tag}를 레지스트리 호출에 필요한 {@code repository}/{@code tag}로 분리한다.
 * 구현계획서 Phase 2 작업 항목 1 — 마지막 콜론을 구분자로 삼는다
 * (예: {@code sb-cc-api:v2.0.25.8612} → repository={@code sb-cc-api}, tag={@code v2.0.25.8612}).
 *
 * <p>{@code image_tag}는 {@code SubVersionUpsertRequest}를 통해 사용자가 직접 입력하는
 * 값이다({@code @NotBlank @Size(max=200)}만 걸려 있음) — Phase 4가 이 값을 그대로 NCR
 * REST 경로에 붙이게 되므로, 여기서 Docker repository/tag 문법을 강제해 경로·쿼리 인젝션
 * 소지가 있는 문자(공백, {@code /../}, {@code ?}, {@code #} 등)를 미리 걸러낸다.
 */
public record ImageReference(String repository, String tag) {

    // https://github.com/distribution/distribution 의 reference 문법을 간략화한 버전.
    private static final Pattern REPOSITORY =
            Pattern.compile("[a-z0-9]+([._-][a-z0-9]+)*(/[a-z0-9]+([._-][a-z0-9]+)*)*");
    private static final Pattern TAG = Pattern.compile("[\\w][\\w.-]{0,127}");

    public static ImageReference parse(String imageTag) {
        int lastColon = imageTag.lastIndexOf(':');
        if (lastColon <= 0 || lastColon == imageTag.length() - 1) {
            throw new IllegalArgumentException("image_tag 형식이 올바르지 않습니다: " + imageTag);
        }
        String repository = imageTag.substring(0, lastColon);
        String tag = imageTag.substring(lastColon + 1);
        if (!REPOSITORY.matcher(repository).matches() || !TAG.matcher(tag).matches()) {
            throw new IllegalArgumentException("image_tag 형식이 올바르지 않습니다: " + imageTag);
        }
        return new ImageReference(repository, tag);
    }
}
