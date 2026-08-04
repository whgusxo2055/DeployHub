package com.deployhub.version.repository;

import com.deployhub.version.entity.MainVersion;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MainVersionRepository extends JpaRepository<MainVersion, String> {

    /**
     * FN-01 목록 조회. {@code version_name}이 {@code 날짜.index} 형식이라 문자열 내림차순이
     * 곧 최신순이다 (구현계획서 Phase 1-3).
     */
    @Query("""
            SELECT m FROM MainVersion m
            WHERE :keyword IS NULL OR m.versionName LIKE CONCAT('%', :keyword, '%')
            ORDER BY m.versionName DESC
            """)
    Page<MainVersion> search(@Param("keyword") String keyword, Pageable pageable);

    /**
     * "직전 메인버전"을 구한다: {@code version_name}이 대상보다 작은 것 중 최대값
     * (구현계획서 Phase 1-4). 변경 여부 계산과 패키징 대상 선정의 기준이 된다.
     */
    Optional<MainVersion> findFirstByVersionNameLessThanOrderByVersionNameDesc(String versionName);
}
