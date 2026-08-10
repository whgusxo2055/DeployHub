package com.deployhub.job.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * FN-10 파일별 정보 (구현계획서 588행). 개별 재다운로드용 보조 정보다 — 기본 창구는
 * {@link PackageFilesResponse#folderUrl()} 폴더 공유 링크 1건이다.
 *
 * <p>서브버전 정보는 {@code package_item.image_tag} → {@code component} → {@code sub_version}
 * 역참조로 채운다. 확정 시점 스냅샷인 {@code image_tag}에 대응하는 컴포넌트가 그 뒤
 * 삭제됐으면(매니페스트 보호가 FAILED Job은 막지 않는다) 두 필드는 null이 된다.
 */
@Builder
public record PackageFileResponse(
        @Schema(description = "SharePoint에 올라간 파일명", example = "sb-cc-api_v2.0.25.8612_1a2b3c4d.tar")
                String fileName,
        @Schema(description = "파일 크기(바이트)") Long fileSize,
        @Schema(description = "이미지 태그", example = "sb-cc-api:v2.0.25.8612") String imageTag,
        @Schema(description = "담당 영역(서브버전) 코드", example = "cc") String subVersionCode,
        @Schema(description = "담당 영역 릴리즈 버전", example = "v2.0.25") String subVersionVersion,
        @Schema(description = "파일 개별 URL (Drive Item webUrl)") String fileUrl) {}
