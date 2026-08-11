package com.deployhub.job.dto;

import com.deployhub.job.entity.PackageItem;
import lombok.Builder;

/** 폴링 응답의 항목별 상태. */
@Builder
public record PackageItemResponse(
        String imageTag, String status, Long fileSize, Integer retryCount, String errorMessage, String fileUrl) {

    public static PackageItemResponse from(PackageItem entity) {
        return PackageItemResponse.builder()
                .imageTag(entity.getImageTag())
                .status(entity.getStatus().name())
                .fileSize(entity.getFileSize())
                .retryCount(entity.getRetryCount())
                .errorMessage(entity.getErrorMessage())
                .fileUrl(entity.getFileUrl())
                .build();
    }
}
