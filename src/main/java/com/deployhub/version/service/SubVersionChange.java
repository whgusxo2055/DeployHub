package com.deployhub.version.service;

import java.util.Map;

/** {@link VersionComparisonService#computeChanges}의 결과 항목 — API DTO가 아닌 도메인 내부 값. */
public record SubVersionChange(boolean changed, Map<String, Boolean> componentChangedByImageTag) {}
