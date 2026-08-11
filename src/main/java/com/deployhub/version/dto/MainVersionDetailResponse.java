package com.deployhub.version.dto;

import java.util.List;
import lombok.Builder;

/** 메인버전 상세 응답 전체. */
@Builder
public record MainVersionDetailResponse(
        MainVersionInfoResponse mainVersion, List<SubVersionResponse> subVersions, DetailSummaryResponse summary) {}
