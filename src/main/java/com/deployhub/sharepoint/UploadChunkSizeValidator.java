package com.deployhub.sharepoint;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 업로드 청크 크기를 기동 시점에 검증한다. {@code StartupChecks}에 넣지 않는 이유는 그게
 * dev 프로필에서 꺼지는데 이 값은 외부 연동과 무관한 순수 설정값이라 꺼질 이유가 없어서다.
 * 기동 실패라는 부작용만을 위한 빈이라 {@link GraphUploadService}는 같은 프로퍼티를 따로 주입받는다.
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
