package com.deployhub.sharepoint;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Graph 설정. {@code driveId}는 미설정을 허용한다({@link GraphApiClient#resolveDriveId()}가 보완).
 * 위임(delegated) 인증이라 client-secret·site-id는 쓰지 않는다 — app-only로 되돌린다면
 * 그때 필드를 다시 추가할 것. <b>시크릿 필드에 {@code @Pattern}·{@code @Size}를 붙이지 말 것</b>:
 * Boot의 바인딩 실패 리포트가 rejected value를 원문 그대로 기동 로그에 찍는다.
 */
@Validated
@ConfigurationProperties(prefix = "deployhub.sharepoint")
public record GraphProperties(
        @NotBlank String tenantId,
        @NotBlank String clientId,
        String driveId,
        @NotBlank String rootPath) {

    @Override
    public String toString() {
        return "GraphProperties[tenantId=%s, driveId=%s, rootPath=%s, clientId=****]"
                .formatted(tenantId, driveId, rootPath);
    }
}
