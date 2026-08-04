package com.deployhub.registry;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 구현계획서 0.6절 NCR 설정. 자격 증명은 이 레코드에만 메모리로 보관하고,
 * {@link #toString()}을 재정의해 로그·예외 메시지에 그대로 찍히는 것을 막는다
 * (Phase 2 작업 항목 1).
 *
 * <p>{@code accessKey}/{@code secretKey}에 {@code @Pattern}·{@code @Size} 같은 값 기반
 * 제약을 추가하지 말 것 — Spring Boot의 바인딩 실패 리포트({@code
 * BindValidationFailureAnalyzer})는 이 레코드의 {@code toString()}을 거치지 않고 rejected
 * value를 원문 그대로 기동 로그에 찍는다. {@code @NotBlank}는 빈 값만 걸러내므로 안전하다.
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
