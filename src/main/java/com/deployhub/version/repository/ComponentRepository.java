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

    List<Component> findBySubVersionIdIn(Collection<Long> subVersionIds);

    /**
     * 메인버전 내 {@code image_tag} 유일성 검증(E-0203)에 쓴다. 이 검증은 메인버전
     * 범위로 한정해야 한다 — 서로 다른 메인버전이 같은 image_tag를 공유하는 것은
     * FN-02 변경 여부 판정이 성립하기 위한 정상 상태다 (구현계획서 0.4절).
     */
    @Query("""
            SELECT c FROM Component c
            WHERE c.subVersionId IN (
                SELECT sv.id FROM SubVersion sv WHERE sv.mainVersionName = :mainVersionName
            )
            """)
    List<Component> findByMainVersionName(@Param("mainVersionName") String mainVersionName);

    /** FN-01 목록의 componentCount 집계용. */
    @Query("""
            SELECT COUNT(c) FROM Component c
            WHERE c.subVersionId IN (
                SELECT sv.id FROM SubVersion sv WHERE sv.mainVersionName = :mainVersionName
            )
            """)
    long countByMainVersionName(@Param("mainVersionName") String mainVersionName);
}
