package com.deployhub.sharepoint;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Graph 설정. {@code driveId}는 미설정을 허용한다({@link GraphApiClient#resolveDriveId()}가 보완).
 * <b>{@code clientSecret}에 {@code @Pattern}·{@code @Size}를 붙이지 말 것</b> —
 * Boot의 바인딩 실패 리포트가 rejected value를 원문 그대로 기동 로그에 찍는다.
 *
 * <p><b>{@code clientSecret}·{@code siteId}는 위임(delegated) 인증에서 쓰이지 않아 필수가 아니다</b> —
 * public client에는 시크릿이 아예 없어서, 필수로 두면 운영자가 안 쓰는 자격증명을 계속
 * {@code .env}에 두거나(회전·폐기 대상에서 잊힌다) 더미 값을 채워 넣게 된다.
 * app-only로 되돌릴 때 {@code @NotBlank}를 다시 붙일 것.
 */
@Validated
@ConfigurationProperties(prefix = "deployhub.sharepoint")
public record GraphProperties(
        @NotBlank String tenantId,
        @NotBlank String clientId,
        String clientSecret,
        String siteId,
        String driveId,
        @NotBlank String rootPath) {

    @Override
    public String toString() {
        return "GraphProperties[tenantId=%s, siteId=%s, driveId=%s, rootPath=%s, clientId=****, clientSecret=****]"
                .formatted(tenantId, siteId, driveId, rootPath);
    }
}
