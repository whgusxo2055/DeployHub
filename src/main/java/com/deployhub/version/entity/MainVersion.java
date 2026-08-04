package com.deployhub.version.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** ERD main_version — 메인버전 (배포 단위). PK는 대리키가 아니라 {@code version_name} 자체다. */
@Entity
@Table(name = "main_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class MainVersion {

    @Id
    @Column(name = "version_name", length = 20)
    private String versionName;

    @Column(name = "release_note", columnDefinition = "TEXT")
    private String releaseNote;

    @Column(name = "sql_script", columnDefinition = "TEXT")
    private String sqlScript;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public void update(String releaseNote, String sqlScript) {
        this.releaseNote = releaseNote;
        this.sqlScript = sqlScript;
        this.updatedAt = Instant.now();
    }
}
