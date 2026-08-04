package com.deployhub.version.repository;

import com.deployhub.version.entity.SubVersion;
import com.deployhub.version.entity.SubmitStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubVersionRepository extends JpaRepository<SubVersion, Long> {

    List<SubVersion> findByMainVersionNameOrderBySortOrderAsc(String mainVersionName);

    Optional<SubVersion> findByMainVersionNameAndCode(String mainVersionName, String code);

    long countByMainVersionName(String mainVersionName);

    /** FN-02-1 패키징 가능 여부 판정 — PENDING 담당 영역 존재 확인. */
    List<SubVersion> findByMainVersionNameAndSubmitStatus(String mainVersionName, SubmitStatus submitStatus);
}
