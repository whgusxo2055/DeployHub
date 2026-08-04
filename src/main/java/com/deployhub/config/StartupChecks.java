package com.deployhub.config;

import com.deployhub.registry.NcrRegistryClient;
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
 * {@code GraphProperties}의 {@code @NotBlank} 검증이 기동 실패로 대신 처리한다.
 *
 * <p>{@code deployhub.startup-checks.enabled=false}로 끌 수 있다 — 실제 키 발급 전에
 * Swagger 명세만 보려는 경우처럼, NCR에 붙을 수 없는 환경에서 임시로 기동하려는 용도다
 * ({@code docs} 프로필 참고). 기본값은 {@code true}라 운영 동작은 바뀌지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "deployhub.startup-checks", name = "enabled", havingValue = "true", matchIfMissing = true)
public class StartupChecks implements ApplicationRunner {

    private final NcrRegistryClient ncrRegistryClient;

    @Override
    public void run(ApplicationArguments args) {
        log.info("NCR Private Endpoint 도달성을 확인합니다.");
        ncrRegistryClient.healthCheck();
        log.info("NCR Private Endpoint 도달 확인 완료.");
    }
}
