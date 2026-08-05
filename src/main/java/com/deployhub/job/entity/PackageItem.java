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
 * <p>Phase 3이 확정 시점 스냅샷으로 생성한다(구현계획서 410행). 상태 갱신은 Phase 4~5가 채운다.
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

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private PackageItemStatus status = PackageItemStatus.PENDING;

    @Builder.Default
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    // Phase 4가 skopeo/NCR 실패 stderr를 여기 채운다. REGISTRY_AUTH_FILE 방식이라
    // accessKey:secretKey가 CLI 인자로는 안 남지만, skopeo가 오류 메시지에 인증 파일
    // 내용을 반영할 가능성까지 배제할 수 없어 PackageDownloadService가 저장 전에
    // CredentialMasker로 마스킹한다(프로젝트 규약: 자격증명은 로그·API 응답 어디에도
    // 남기지 않는다). 응답 시점 마스킹은 늦다 — DB에 평문이 이미 남는다.
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "file_url", length = 500)
    private String fileUrl;

    /** FN-05/FN-06-1/FN-07 실패 처리 — 자동 재시도 여부는 호출자(PackageDownloadService)가 판단한다. */
    public void markFailed(String errorMessage) {
        this.status = PackageItemStatus.FAILED;
        this.errorMessage = errorMessage;
    }

    /** FN-06-1 다운로드 성공 — file_size는 실제 산출 .tar 크기다(매니페스트 예상치가 아님). */
    public void markDownloaded(long fileSize) {
        this.status = PackageItemStatus.DOWNLOADED;
        this.fileSize = fileSize;
        this.errorMessage = null;
    }

    /** FN-09 업로드 성공 — fileUrl은 마지막 청크 응답의 Drive Item webUrl이다(구현계획서 559행). */
    public void markUploaded(String fileUrl) {
        this.status = PackageItemStatus.UPLOADED;
        this.fileUrl = fileUrl;
        this.errorMessage = null;
    }

    /** FN-07 자동 재시도 — 백오프 대기 전에 호출해 폴링 클라이언트가 시도 횟수를 바로 본다. */
    public void incrementRetryCount() {
        this.retryCount = this.retryCount + 1;
    }

    /**
     * FN-07 수동 재시도 대상 초기화. 일반적으로 DOWNLOADED/UPLOADED 항목은 대상에서
     * 제외되지만(PackageJobService.resolveRetryTargets), 작업 디렉터리 소실(E-0703) +
     * force=true 경로는 그 항목들까지 전건 되돌린다 — 이때 fileSize/fileUrl을 안 지우면
     * 재수집 중인 항목이 이전 다운로드 크기·SharePoint 업로드 URL을 그대로 노출한다.
     */
    public void resetForRetry() {
        this.status = PackageItemStatus.PENDING;
        this.errorMessage = null;
        this.fileSize = null;
        this.fileUrl = null;
    }
}
