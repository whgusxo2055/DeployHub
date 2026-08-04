package com.deployhub.job.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ERD package_job — 패키지 Job (패키징 실행 단위 · 메인버전당 1건, PK = version_name).
 *
 * <p>Phase 1은 이 엔티티를 "해당 메인버전에 Job이 존재하는지" 확인하는 용도로만 참조한다.
 * 생성·상태 전이 로직(과 {@code builder()} 실사용)은 Phase 3에서 채운다.
 */
@Entity
@Table(name = "package_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PackageJob {

    @Id
    @Column(name = "version_name", length = 20)
    private String versionName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private JobStatus status;

    @Column(name = "sp_folder_id", length = 200)
    private String spFolderId;

    @Column(name = "sp_folder_url", length = 500)
    private String spFolderUrl;

    @Column(name = "created_by", length = 100, nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** 매니페스트 보호(Phase 1 E-0204)에서 "진행 중이거나 완료된" Job인지 판정한다. FAILED만 예외다. */
    public boolean blocksManifestModification() {
        return status != JobStatus.FAILED;
    }
}
