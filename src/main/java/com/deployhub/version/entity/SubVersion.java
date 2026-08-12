package com.deployhub.version.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 서브버전(모듈 릴리즈·제출 단위). 담당 영역과 1:1이며 UNIQUE(main_version_name, code)다. */
@Entity
@Table(name = "sub_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class SubVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "main_version_name", length = 20, nullable = false)
    private String mainVersionName;

    @Column(name = "code", length = 50, nullable = false)
    private String code;

    @Column(name = "version", length = 50, nullable = false)
    private String version;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "submit_status", length = 20, nullable = false)
    private SubmitStatus submitStatus = SubmitStatus.PENDING;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    public void update(String version, String note, Integer sortOrder) {
        this.version = version;
        this.note = note;
        this.sortOrder = sortOrder;
        // 등록·수정하면 제출 상태는 PENDING으로 되돌아간다.
        this.submitStatus = SubmitStatus.PENDING;
        this.submittedAt = null;
    }

    public void changeSubmitStatus(SubmitStatus status) {
        this.submitStatus = status;
        this.submittedAt = Instant.now();
    }
}
