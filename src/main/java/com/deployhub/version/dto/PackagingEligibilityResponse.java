package com.deployhub.version.dto;

import java.util.List;
import lombok.Builder;

/** 패키징 가능 여부 응답. */
@Builder
public record PackagingEligibilityResponse(boolean eligible, List<String> blockingSubVersionCodes) {}
