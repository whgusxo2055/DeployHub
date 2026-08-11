package com.deployhub.version.dto;

import lombok.Builder;

/** 메인버전 목록 항목. */
@Builder
public record MainVersionSummaryResponse(
        String versionName, int subVersionCount, int componentCount, JobSummaryResponse lastJob) {}
