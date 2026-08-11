package com.deployhub.sharepoint;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Graph/SharePoint 설정. {@code driveId}는 미설정을 허용한다({@link GraphApiClient#resolveDriveId()}가 보완).
 * <b>{@code clientSecret}에 {@code @Pattern}·{@code @Size}를 붙이지 말 것</b> —
 * Boot의 바인딩 실패 리포트가 rejected value를 원문 그대로 기동 로그에 찍는다.
 */
@Validated
@ConfigurationProperties(prefix = "deployhub.sharepoint")
public record GraphProperties(
        @NotBlank String tenantId,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String siteId,
        String driveId,
        @NotBlank String rootPath) {

    @Override
    public String toString() {
        return "GraphProperties[tenantId=%s, siteId=%s, driveId=%s, rootPath=%s, clientId=****, clientSecret=****]"
                .formatted(tenantId, siteId, driveId, rootPath);
    }
}
