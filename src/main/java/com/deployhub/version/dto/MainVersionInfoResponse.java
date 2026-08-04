package com.deployhub.version.dto;

import com.deployhub.version.entity.MainVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;

@Builder
public record MainVersionInfoResponse(
        @Schema(description = "메인버전명") String versionName,
        String releaseNote,
        String sqlScript,
        Instant createdAt,
        Instant updatedAt) {

    public static MainVersionInfoResponse from(MainVersion entity) {
        return MainVersionInfoResponse.builder()
                .versionName(entity.getVersionName())
                .releaseNote(entity.getReleaseNote())
                .sqlScript(entity.getSqlScript())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
