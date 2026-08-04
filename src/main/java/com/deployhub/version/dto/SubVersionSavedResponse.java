package com.deployhub.version.dto;

import java.util.List;
import lombok.Builder;

/** 서브버전 등록·수정 API의 응답. 변경 여부(changed)는 FN-02 조회 시점에만 계산한다. */
@Builder
public record SubVersionSavedResponse(
        Long id, String code, String version, String note, Integer sortOrder, List<String> imageTags) {}
