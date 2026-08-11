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

/** Job 내 산출물별 처리 상태. 매니페스트 확정 시점의 스냅샷이다. */
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

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private PackageItemStatus status = PackageItemStatus.PENDING;

    @Builder.Default
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    // skopeo/NCR 실패 stderr가 들어온다 — 저장 <b>전에</b> CredentialMasker로 마스킹할 것.
    // 응답 시점 마스킹은 늦다(DB에 평문이 이미 남는다).
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "file_url", length = 500)
    private String fileUrl;

    /** 실패 처리 — 자동 재시도 여부는 호출자가 판단한다. */
    public void markFailed(String errorMessage) {
        this.status = PackageItemStatus.FAILED;
        this.errorMessage = errorMessage;
    }

    /** 다운로드 성공 — {@code fileSize}는 실제 산출 tar 크기다(매니페스트 예상치가 아님). */
    public void markDownloaded(long fileSize) {
        this.status = PackageItemStatus.DOWNLOADED;
        this.fileSize = fileSize;
        this.errorMessage = null;
    }

    /** 업로드 성공 — {@code fileUrl}은 마지막 청크 응답의 Drive Item webUrl이다. */
    public void markUploaded(String fileUrl) {
        this.status = PackageItemStatus.UPLOADED;
        this.fileUrl = fileUrl;
        this.errorMessage = null;
    }

    /** 백오프 대기 <b>전에</b> 호출한다 — 폴링 클라이언트가 시도 횟수를 바로 보게 된다. */
    public void incrementRetryCount() {
        this.retryCount = this.retryCount + 1;
    }

    /**
     * 재시도 대상 초기화. {@code fileSize}/{@code fileUrl}까지 지워야 한다 — 전건 재수집 경로에서
     * 안 지우면 재수집 중인 항목이 이전 크기와 업로드 URL을 그대로 노출한다.
     */
    public void resetForRetry() {
        this.status = PackageItemStatus.PENDING;
        this.errorMessage = null;
        this.fileSize = null;
        this.fileUrl = null;
    }
}
