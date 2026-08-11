package com.deployhub.version.dto;

import lombok.Builder;

/** 상세 조회 summary. component에 size_bytes가 없어 total_size는 제공하지 않는다. */
@Builder
public record DetailSummaryResponse(int subVersionCount, int componentCount, int missingCount) {}
