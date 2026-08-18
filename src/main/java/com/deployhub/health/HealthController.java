package com.deployhub.health;

import com.deployhub.registry.NcrRegistryClient;
import com.deployhub.sharepoint.GraphApiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 외부 저장소 연결 상태 확인. 실패 시 예외를 그대로 던져 E-04xx로 응답한다 — NCR은 재시도 없이
 * 1회 호출하고, Graph는 {@code GraphApiClient.get}의 재시도 정책을 그대로 탄다.
 */
@RestController
@Tag(name = "외부 저장소 연동")
@RequiredArgsConstructor
public class HealthController {

    private final NcrRegistryClient ncrRegistryClient;
    private final GraphApiClient graphApiClient;

    @Operation(summary = "NCR 연결 상태 확인 (FN-04-1)")
    @ApiResponse(responseCode = "502", description = "E-0404: Private Endpoint 도달 불가")
    @GetMapping("/api/health/registry")
    public HealthResponse registryHealth() {
        ncrRegistryClient.healthCheck();
        return new HealthResponse(true);
    }

    @Operation(summary = "Microsoft Graph / SharePoint 연결 상태 확인 (FN-04-5)")
    @ApiResponse(responseCode = "502", description = "E-0451: 토큰 발급 실패")
    @ApiResponse(responseCode = "403", description = "E-0452: 권한 부족")
    @ApiResponse(responseCode = "503", description = "E-0453: 일시적 응답 불가")
    @GetMapping("/api/health/sharepoint")
    public HealthResponse sharepointHealth() {
        graphApiClient.healthCheck();
        return new HealthResponse(true);
    }
}
