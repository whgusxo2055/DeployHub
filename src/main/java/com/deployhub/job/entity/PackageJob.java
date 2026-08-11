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

/** 패키징 실행 단위. 메인버전당 1건이라 PK가 {@code version_name}이다. */
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

    /** 매니페스트 보호에서 "진행 중이거나 완료된" Job인지 판정한다. FAILED만 예외다. */
    public boolean blocksManifestModification() {
        return status != JobStatus.FAILED;
    }

    /**
     * 종료 상태면 {@code finishedAt}을 찍고, 아니면 비운다. 비종료로 가면 {@code deletedAt}도 함께
     * 비운다 — 다시 도는 Job은 정의상 정리된 상태가 아니다. 여기서 안 비우면 정리 후 재시도해 성공한 Job이
     * {@code deleted_at}을 단 채로 남아 정리 배치의 alive 필터에서 영구히 빠지고, 멀쩡한 URL이 "만료됨"으로 표시된다.
     */
    public void changeStatus(JobStatus next) {
        boolean terminal = next == JobStatus.DONE || next == JobStatus.FAILED;
        this.status = next;
        this.finishedAt = terminal ? Instant.now() : null;
        if (!terminal) {
            this.deletedAt = null;
        }
    }

    /**
     * 재실행 시 기존 행을 초기화한다. {@code sp_folder_*}는 같은 이름 폴더를 재사용하므로 그대로 둔다.
     * {@code createdAt}은 건드리지 말 것 — {@code updatable = false}라 대입해도 UPDATE에서 조용히 빠져
     * 응답 DTO와 실제 저장값이 어긋난다.
     */
    public void resetForRerun(String createdBy) {
        this.status = JobStatus.PENDING;
        this.createdBy = createdBy;
        this.finishedAt = null;
        this.deletedAt = null;
    }

    /**
     * SharePoint 폴더를 지운 시각을 남긴다. 행도 {@code sp_folder_*}도 지우지 않는다 —
     * 어느 폴더가 정리됐는지가 감사 흔적이다.
     */
    public void markDeleted() {
        this.deletedAt = Instant.now();
    }

    /** 폴더 확보(생성 또는 재사용) 결과. 재사용이면 같은 값을 다시 써도 무해하다. */
    public void applyFolder(String spFolderId, String spFolderUrl) {
        this.spFolderId = spFolderId;
        this.spFolderUrl = spFolderUrl;
    }
}
