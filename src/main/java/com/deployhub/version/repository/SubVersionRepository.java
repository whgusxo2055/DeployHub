package com.deployhub.version.repository;

import com.deployhub.version.entity.SubVersion;
import com.deployhub.version.entity.SubmitStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubVersionRepository extends JpaRepository<SubVersion, Long> {

    List<SubVersion> findByMainVersionNameOrderBySortOrderAsc(String mainVersionName);

    Optional<SubVersion> findByMainVersionNameAndCode(String mainVersionName, String code);

    long countByMainVersionName(String mainVersionName);

    /** 목록 조회용 일괄 집계 — 항목마다 count를 치면 page size에 비례해 쿼리가 는다. */
    @Query("""
            SELECT new com.deployhub.version.repository.VersionCount(s.mainVersionName, COUNT(s))
            FROM SubVersion s
            WHERE s.mainVersionName IN :mainVersionNames
            GROUP BY s.mainVersionName
            """)
    List<VersionCount> countByMainVersionNames(@Param("mainVersionNames") Collection<String> mainVersionNames);

    /** 패키징 가능 여부 판정 — PENDING 담당 영역 존재 확인. */
    List<SubVersion> findByMainVersionNameAndSubmitStatus(String mainVersionName, SubmitStatus submitStatus);
}
