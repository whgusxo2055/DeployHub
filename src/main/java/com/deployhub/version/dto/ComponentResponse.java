package com.deployhub.version.dto;

import lombok.Builder;

/** 계층 응답의 컴포넌트 항목. */
@Builder
public record ComponentResponse(String imageTag, Integer sortOrder, boolean changed) {}
