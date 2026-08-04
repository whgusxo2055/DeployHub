package com.deployhub.version.service;

import java.util.List;

/** {@link PackagingEligibilityService#evaluate}의 결과. */
public record PackagingEligibility(boolean eligible, List<String> blockingSubVersionCodes) {}
