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
 * <p>Phase 3이 생성·재사용·상태 전이 로직을 채운다 (구현계획서 Phase 3-1).
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

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private JobStatus status = JobStatus.PENDING;

    @Column(name = "sp_folder_id", length = 200)
    private String spFolderId;

    @Column(name = "sp_folder_url", length = 500)
    private String spFolderUrl;

    @Column(name = "created_by", length = 100, nullable = false)
    private String createdBy;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** 매니페스트 보호(Phase 1 E-0204)에서 "진행 중이거나 완료된" Job인지 판정한다. FAILED만 예외다. */
    public boolean blocksManifestModification() {
        return status != JobStatus.FAILED;
    }

    /** 종료 상태(DONE/FAILED)면 finishedAt을 찍고, 아니면 비운다. */
    public void changeStatus(JobStatus next) {
        this.status = next;
        this.finishedAt = (next == JobStatus.DONE || next == JobStatus.FAILED) ? Instant.now() : null;
    }

    /**
     * FN-11 재실행(FAILED 재시도, force=true로 DONE 재생성)에서 기존 행을 초기화한다.
     * sp_folder_id/sp_folder_url은 그대로 둔다 — FN-08이 같은 이름 폴더를 재사용하므로
     * 공유 링크가 이미 살아 있다(구현계획서 405행). {@code createdAt}은 건드리지 않는다 —
     * 컬럼이 {@code updatable = false}라 대입해도 UPDATE 문에서 조용히 빠져, 여기서
     * 바꾸면 반환하는 DTO와 실제 저장값이 어긋난다(응답에는 새 시각, DB엔 옛 시각).
     * 구현계획서 405행이 재생성 시 초기화하라고 명시한 것도 {@code finished_at}뿐이다.
     */
    public void resetForRerun(String createdBy) {
        this.status = JobStatus.PENDING;
        this.createdBy = createdBy;
        this.finishedAt = null;
        this.deletedAt = null;
    }

    /** FN-08 폴더 확보(생성 또는 재사용) 결과를 기록한다. 재사용이면 같은 값을 다시 써도 무해하다. */
    public void applyFolder(String spFolderId, String spFolderUrl) {
        this.spFolderId = spFolderId;
        this.spFolderUrl = spFolderUrl;
    }
}
