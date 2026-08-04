package com.deployhub.health;

import com.deployhub.common.ApiException;
import com.deployhub.registry.NcrRegistryClient;
import com.deployhub.sharepoint.GraphApiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 구현계획서 Phase 2 신규 API. 실패 시 예외를 그대로 던져 E-04xx로 응답한다 (즉시 실패, 재시도 없음).
 *
 * <p>이 엔드포인트는 인증 없이 호출 가능하고(앱 전체가 아직 무인증) 호출될 때마다 실제
 * NCR/Graph로 아웃바운드 호출을 낸다. 짧은 TTL 캐시 없이 두면 무인증 호출자가 반복 요청만으로
 * 외부 서비스에 부하를 증폭시키거나(Graph 429 유발 등) 워커 스레드를 붙잡을 수 있어, 결과를
 * 30초간 캐시해 호출당 실제 아웃바운드 호출 빈도를 제한한다.
 */
@RestController
@Tag(name = "외부 저장소 연동")
public class HealthController {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final NcrRegistryClient ncrRegistryClient;
    private final GraphApiClient graphApiClient;
    private final AtomicReference<CachedCheck> registryCache = new AtomicReference<>();
    private final AtomicReference<CachedCheck> sharepointCache = new AtomicReference<>();

    public HealthController(NcrRegistryClient ncrRegistryClient, GraphApiClient graphApiClient) {
        this.ncrRegistryClient = ncrRegistryClient;
        this.graphApiClient = graphApiClient;
    }

    @Operation(summary = "NCR 연결 상태 확인 (FN-04-1)")
    @ApiResponse(responseCode = "502", description = "E-0404: Private Endpoint 도달 불가")
    @GetMapping("/api/health/registry")
    public HealthResponse registryHealth() {
        runCached(registryCache, ncrRegistryClient::healthCheck);
        return new HealthResponse(true);
    }

    @Operation(summary = "Microsoft Graph / SharePoint 연결 상태 확인 (FN-04-5)")
    @ApiResponse(responseCode = "502", description = "E-0451: 토큰 발급 실패")
    @ApiResponse(responseCode = "403", description = "E-0452: 권한 부족")
    @ApiResponse(responseCode = "503", description = "E-0453: 일시적 응답 불가")
    @GetMapping("/api/health/sharepoint")
    public HealthResponse sharepointHealth() {
        runCached(sharepointCache, graphApiClient::healthCheck);
        return new HealthResponse(true);
    }

    private void runCached(AtomicReference<CachedCheck> cacheRef, Runnable check) {
        Instant now = Instant.now();
        CachedCheck cached = cacheRef.get();
        if (cached != null && cached.computedAt().plus(CACHE_TTL).isAfter(now)) {
            if (cached.failure() != null) {
                throw cached.failure();
            }
            return;
        }
        try {
            check.run();
            cacheRef.set(new CachedCheck(now, null));
        } catch (ApiException ex) {
            cacheRef.set(new CachedCheck(now, ex));
            throw ex;
        }
    }

    private record CachedCheck(Instant computedAt, ApiException failure) {}
}
