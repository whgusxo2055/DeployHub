package com.deployhub.job.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ERD package_item — Job 내 산출물별 처리 상태 (PK = version_name, image_tag).
 *
 * <p>Phase 1에서는 매핑만 하고 실제 생성·상태 갱신은 Phase 3~5에서 채운다.
 */
@Entity
@Table(name = "package_item")
@IdClass(PackageItemId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PackageItem {

    @Id
    @Column(name = "version_name", length = 20)
    private String versionName;

    @Id
    @Column(name = "image_tag", length = 200)
    private String imageTag;

    @Column(name = "file_size")
    private Long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private PackageItemStatus status;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "file_url", length = 500)
    private String fileUrl;
}
