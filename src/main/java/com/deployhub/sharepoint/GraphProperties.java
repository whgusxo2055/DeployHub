package com.deployhub.sharepoint;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 구현계획서 0.6절 Microsoft Graph / SharePoint 설정. {@code driveId}는 미설정을 허용한다
 * ({@link GraphApiClient#resolveDriveId()}가 사이트 조회로 보완한다).
 *
 * <p>{@code clientSecret}에 {@code @Pattern}·{@code @Size} 같은 값 기반 제약을 추가하지
 * 말 것 — Spring Boot의 바인딩 실패 리포트는 이 레코드의 {@code toString()}을 거치지 않고
 * rejected value를 원문 그대로 기동 로그에 찍는다. {@code @NotBlank}는 빈 값만 걸러내므로 안전하다.
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
