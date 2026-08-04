package com.deployhub.health;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "외부 저장소 연결 상태")
public record HealthResponse(@Schema(description = "정상 여부", example = "true") boolean healthy) {}
