package com.deployhub.version.dto;

import com.deployhub.job.entity.PackageJob;
import java.time.Instant;
import lombok.Builder;

/** 목록의 last_job. package_job PK가 version_name이라 메인버전당 최대 1건이다. */
@Builder
public record JobSummaryResponse(String status, Instant createdAt, Instant finishedAt) {

    public static JobSummaryResponse from(PackageJob entity) {
        return JobSummaryResponse.builder()
                .status(entity.getStatus().name())
                .createdAt(entity.getCreatedAt())
                .finishedAt(entity.getFinishedAt())
                .build();
    }
}
