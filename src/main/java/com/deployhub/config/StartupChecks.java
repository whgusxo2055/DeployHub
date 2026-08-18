package com.deployhub.config;

import com.deployhub.registry.NcrProperties;
import com.deployhub.registry.NcrRegistryClient;
import com.deployhub.sharepoint.GraphApiClient;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 기동 시 외부 연동 사전 점검(fail-fast) — NCR 도달성, skopeo 실행 가능 여부, Graph 인증.
 * 첫 Job까지 미루면 사용자가 패키징을 시작한 뒤에야 오설정을 알게 된다.
 * {@code deployhub.startup-checks.enabled=false}로 끌 수 있다(NCR에 붙을 수 없는 환경용, 기본은 true).
 * 자격 증명 존재 검증은 {@code @NotBlank}가, 청크 크기 검증은 {@code UploadChunkSizeValidator}가 맡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "deployhub.startup-checks", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StartupChecks implements ApplicationRunner {

    private final NcrRegistryClient ncrRegistryClient;
    private final NcrProperties ncrProperties;
    private final GraphApiClient graphApiClient;

    @Override
    public void run(ApplicationArguments args) {
        // NcrRegistryClient가 테스트용 평문 HTTP 스킴을 허용하므로, 운영에 http://가 들어가면
        // Basic 헤더와 --src-tls-verify=false가 무방비로 나간다 — endpoint에도 HTTPS를 강제한다.
        if (ncrProperties.endpoint().regionMatches(true, 0, "http://", 0, 7)) {
            throw new IllegalStateException(
                    "NCR_ENDPOINT가 평문 HTTP입니다 — 자격 증명이 노출됩니다. HTTPS 엔드포인트를 쓰세요.");
        }

        log.info("NCR Endpoint 도달성을 확인합니다.");
        ncrRegistryClient.healthCheck();
        log.info("NCR Endpoint 도달 확인 완료.");

        log.info("skopeo 실행 가능 여부를 확인합니다: {}", ncrProperties.cliPath());
        // 경로 구분자 없는 이름은 PATH로 찾아 실행되므로 CWD 기준 isExecutable이 잘못 실패한다 —
        // 그 경우는 건너뛰고 실제 실행 시점의 실패에 맡긴다.
        if (ncrProperties.cliPath().contains("/") || ncrProperties.cliPath().contains("\\")) {
            if (!Files.isExecutable(Path.of(ncrProperties.cliPath()))) {
                throw new IllegalStateException(
                        "E-0605: skopeo 실행 파일을 찾을 수 없거나 실행 권한이 없습니다: " + ncrProperties.cliPath());
            }
        }
        log.info("skopeo 확인 완료.");

        // 위임 인증은 refresh token 파일에 의존한다 — 파일 부재·만료·스코프 오류가 첫 Job까지
        // 숨지 않도록 여기서 실제 호출로 확인한다(토큰 발급 + Graph 왕복 한 번).
        log.info("Microsoft Graph 인증 및 드라이브 접근을 확인합니다.");
        graphApiClient.healthCheck();
        log.info("Microsoft Graph 확인 완료.");
    }
}
