package com.deployhub.version.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

/** 계층 응답의 서브버전 항목. */
@Builder
public record SubVersionResponse(
        Long id,
        String code,
        String version,
        String note,
        Integer sortOrder,
        String submitStatus,
        Instant submittedAt,
        boolean changed,
        List<ComponentResponse> components) {}
