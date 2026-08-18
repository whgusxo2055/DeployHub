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

/** 메인버전(배포 단위). PK는 대리키가 아니라 {@code version_name} 자체다. */
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

    /**
     * 정렬·"직전 버전" 판정 전용 파생 컬럼. {@code version_name}을 그대로 문자열 비교하면
     * index가 두 자리가 되는 순간 뒤집힌다('2026.08.05-10' &lt; '2026.08.05-2'). 등록 시
     * {@link #sortKeyOf}로 채우고 그 뒤로는 바뀌지 않는다(version_name이 PK라 수정 불가).
     */
    @Column(name = "sort_key", length = 20, nullable = false, updatable = false)
    private String sortKey;

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

    /**
     * {@code 2026.08.05-10} → {@code 2026.08.05.010}. 구분자('.'·'-')를 하나로 통일하고 index를
     * 0으로 채워, 문자열 비교가 곧 날짜·index 순서가 되게 한다. 형식은 등록 시점에
     * {@code MainVersionCreateRequest}의 정규식이 이미 강제한다(index는 최대 3자리).
     */
    public static String sortKeyOf(String versionName) {
        String date = versionName.substring(0, 10);
        String index = versionName.length() > 10 ? versionName.substring(11) : "0";
        return "%s.%03d".formatted(date, Integer.parseInt(index));
    }

    public void update(String releaseNote, String sqlScript) {
        this.releaseNote = releaseNote;
        this.sqlScript = sqlScript;
        this.updatedAt = Instant.now();
    }
}
