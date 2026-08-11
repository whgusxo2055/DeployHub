package com.deployhub.registry;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * NCR 설정. 자격 증명이 로그·예외 메시지에 찍히지 않도록 {@link #toString()}을 재정의한다.
 * <b>{@code accessKey}/{@code secretKey}에 {@code @Pattern}·{@code @Size}를 붙이지 말 것</b> —
 * Boot의 바인딩 실패 리포트는 {@code toString()}을 거치지 않고 rejected value를 원문 그대로 기동 로그에 찍는다.
 * {@code @NotBlank}는 빈 값만 걸러내므로 안전하다.
 */
@Validated
@ConfigurationProperties(prefix = "deployhub.registry")
public record NcrProperties(
        @NotBlank String endpoint, @NotBlank String accessKey, @NotBlank String secretKey, @NotBlank String cliPath) {

    @Override
    public String toString() {
        return "NcrProperties[endpoint=%s, cliPath=%s, accessKey=****, secretKey=****]".formatted(endpoint, cliPath);
    }
}
