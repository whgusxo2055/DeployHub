package com.deployhub.config;

import com.deployhub.registry.NcrProperties;
import com.deployhub.registry.NcrRegistryClient;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 구현계획서 Phase 0 작업 항목 6 — 기동 시 사전 점검(fail-fast). NCR은 VPC 내부 Private
 * Endpoint 전제라 서브넷·ACG 오설정이 있으면 이후 모든 Phase가 실패하므로 기동 시점에
 * 조기 검출한다 (E-0404). 필수 자격 증명 존재 여부(E-0403)는 {@code NcrProperties}·
 * {@code GraphProperties}의 {@code @NotBlank} 검증이 기동 실패로 대신 처리한다. skopeo
 * 실행 가능 여부(E-0605, Phase 4)도 여기서 조기 검출한다 — 첫 Job이 뜰 때까지 미루면
 * 사용자가 패키징을 시작한 뒤에야 오설정을 알게 된다.
 *
 * <p>{@code deployhub.startup-checks.enabled=false}로 끌 수 있다 — 실제 키 발급 전에
 * Swagger 명세만 보려는 경우처럼, NCR에 붙을 수 없는 환경에서 임시로 기동하려는 용도다
 * ({@code dev} 프로필 참고). 기본값은 {@code true}라 운영 동작은 바뀌지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "deployhub.startup-checks", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StartupChecks implements ApplicationRunner {

    private final NcrRegistryClient ncrRegistryClient;
    private final NcrProperties ncrProperties;

    @Override
    public void run(ApplicationArguments args) {
        // 자격 증명이 평문 HTTP로 나가는 걸 막는다 — NcrRegistryClient가 로컬 테스트용
        // registry:2를 가리키도록 스킴을 그대로 받아들이게 되어 있어(Phase 4), 운영에서
        // 실수로 NCR_ENDPOINT=http://...가 들어가면 Basic 인증 헤더와 skopeo
        // --src-tls-verify=false가 무방비로 나간다. fetchBearerToken이 realm에 대해
        // 이미 적용 중인 HTTPS 강제를 endpoint 자체에도 적용한다.
        if (ncrProperties.endpoint().regionMatches(true, 0, "http://", 0, 7)) {
            throw new IllegalStateException(
                    "NCR_ENDPOINT가 평문 HTTP입니다 — 자격 증명이 노출됩니다. HTTPS 엔드포인트를 쓰세요.");
        }

        log.info("NCR Private Endpoint 도달성을 확인합니다.");
        ncrRegistryClient.healthCheck();
        log.info("NCR Private Endpoint 도달 확인 완료.");

        log.info("skopeo 실행 가능 여부를 확인합니다: {}", ncrProperties.cliPath());
        // cliPath가 경로 구분자 없는 이름(예: "skopeo")이면 PATH로 찾아 실행되므로 CWD
        // 기준 isExecutable 검사가 잘못 실패한다 — 그 경우는 검사를 건너뛰고 실제 실행
        // 시점의 실패(E-0605, PackageDownloadService.runSkopeo)에 맡긴다.
        if (ncrProperties.cliPath().contains("/") || ncrProperties.cliPath().contains("\\")) {
            if (!Files.isExecutable(Path.of(ncrProperties.cliPath()))) {
                throw new IllegalStateException(
                        "E-0605: skopeo 실행 파일을 찾을 수 없거나 실행 권한이 없습니다: " + ncrProperties.cliPath());
            }
        }
        log.info("skopeo 확인 완료.");
    }
}
