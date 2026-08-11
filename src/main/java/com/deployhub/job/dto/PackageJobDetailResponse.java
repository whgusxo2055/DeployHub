package com.deployhub.job.dto;

import java.util.List;
import lombok.Builder;

/** 폴링 대상 전체 응답. 항목 조회용 별도 엔드포인트는 두지 않는다. */
@Builder
public record PackageJobDetailResponse(PackageJobResponse job, List<PackageItemResponse> items) {}
