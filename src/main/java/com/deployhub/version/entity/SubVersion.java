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
import java.util.Objects;
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

    /**
     * 값이 실제로 달라졌을 때만 제출 상태를 되돌린다. 배치 API 특성상 운영자가 전체 목록을 그대로
     * 다시 PUT하기 쉬운데, 무조건 되돌리면 이미 제출한 담당 영역이 전부 PENDING이 되고
     * {@code submitted_at}이 날아가 패키징이 E-0305로 막힌다.
     */
    public void update(String version, String note, Integer sortOrder) {
        boolean changed = !Objects.equals(this.version, version)
                || !Objects.equals(this.note, note)
                || !Objects.equals(this.sortOrder, sortOrder);
        this.version = version;
        this.note = note;
        this.sortOrder = sortOrder;
        if (changed) {
            this.submitStatus = SubmitStatus.PENDING;
            this.submittedAt = null;
        }
    }

    /** 컴포넌트 목록이 바뀐 경우에도 같은 규칙을 적용한다(호출자가 판단해 부른다). */
    public void resetSubmitStatus() {
        this.submitStatus = SubmitStatus.PENDING;
        this.submittedAt = null;
    }

    /** PENDING으로 되돌릴 때는 제출 시각을 남기지 않는다 — "제출됨"의 근거가 돼야 한다. */
    public void changeSubmitStatus(SubmitStatus status) {
        this.submitStatus = status;
        this.submittedAt = status == SubmitStatus.PENDING ? null : Instant.now();
    }
}
