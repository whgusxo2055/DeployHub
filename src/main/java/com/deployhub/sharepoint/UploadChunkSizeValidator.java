package com.deployhub.sharepoint;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * FN-09 업로드 청크 크기를 기동 시점에 검증한다(E-1108). {@code StartupChecks}에 넣지
 * 않는다 — 그건 {@code deployhub.startup-checks.enabled=false}(dev 프로필)로 꺼지는데,
 * 이 값은 외부 연동 도달성과 무관한 순수 설정값이라 꺼질 이유가 없다(코드리뷰로 발견 —
 * dev 프로필에서 정작 이 검증이 통째로 비활성화되는 문제).
 *
 * <p>{@link GraphUploadService}는 같은 프로퍼티를 별도로 {@code @Value} 주입받는다 — 이
 * 검증기는 부작용(기동 실패)만을 위해 존재하고, 그래서 {@code new GraphUploadService(...)}로
 * 직접 생성하는 단위 테스트는 Spring 컨텍스트를 거치지 않아 이 검증에 걸리지 않는다(작은
 * 테스트용 청크 크기를 자유롭게 쓸 수 있는 이유).
 */
@Component
public class UploadChunkSizeValidator {

    private static final long CHUNK_SIZE_MULTIPLE = 327_680L; // 320 KiB
    private static final long MAX_CHUNK_SIZE = 62_914_560L; // Graph 권장 상한 60 MiB

    public UploadChunkSizeValidator(@Value("${deployhub.upload.chunk-size}") long chunkSize) {
        if (chunkSize <= 0 || chunkSize % CHUNK_SIZE_MULTIPLE != 0 || chunkSize > MAX_CHUNK_SIZE) {
            throw new IllegalStateException(
                    "E-1108: UPLOAD_CHUNK_SIZE는 320 KiB(327680)의 양의 배수여야 하며 60 MiB(62914560) 이하여야 합니다: "
                            + chunkSize);
        }
    }
}
