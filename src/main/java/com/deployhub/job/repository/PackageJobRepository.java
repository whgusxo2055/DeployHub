package com.deployhub.job.repository;

import com.deployhub.job.entity.PackageJob;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackageJobRepository extends JpaRepository<PackageJob, String> {
    // findById(versionName)만으로 충분하다 (PK = version_name, 메인버전당 1건).
    // Phase 3에서 생성·재사용(force) 로직이 이 리포지토리를 확장한다.
}
