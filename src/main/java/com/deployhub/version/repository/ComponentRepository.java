package com.deployhub.version.repository;

import com.deployhub.version.entity.Component;
import com.deployhub.version.entity.ComponentId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ComponentRepository extends JpaRepository<Component, ComponentId> {

    List<Component> findBySubVersionIdOrderBySortOrderAsc(Long subVersionId);

    /** 정렬이 없으면 getDetail이 컴포넌트를 PK 순으로 돌려준다 — image_tag 대조가 _bin이라 대문자가 앞선다. */
    List<Component> findBySubVersionIdInOrderBySortOrderAsc(Collection<Long> subVersionIds);

    /**
     * 메인버전 내 {@code image_tag} 유일성 검증용. 반드시 메인버전 범위로 한정할 것 —
     * 서로 다른 메인버전이 같은 태그를 공유하는 건 변경 여부 판정이 성립하기 위한 정상 상태다.
     */
    @Query("""
            SELECT c FROM Component c
            WHERE c.subVersionId IN (
                SELECT sv.id FROM SubVersion sv WHERE sv.mainVersionName = :mainVersionName
            )
            """)
    List<Component> findByMainVersionName(@Param("mainVersionName") String mainVersionName);

    /** 목록 조회용 일괄 집계 — 항목마다 count를 치면 page size에 비례해 쿼리가 는다. */
    @Query("""
            SELECT new com.deployhub.version.repository.VersionCount(sv.mainVersionName, COUNT(c))
            FROM Component c, SubVersion sv
            WHERE c.subVersionId = sv.id AND sv.mainVersionName IN :mainVersionNames
            GROUP BY sv.mainVersionName
            """)
    List<VersionCount> countByMainVersionNames(@Param("mainVersionNames") Collection<String> mainVersionNames);
}
