package com.deployhub.job.repository;

import com.deployhub.job.entity.PackageItem;
import com.deployhub.job.entity.PackageItemId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackageItemRepository extends JpaRepository<PackageItem, PackageItemId> {

    List<PackageItem> findByVersionNameOrderByImageTagAsc(String versionName);

    /** 목록 API가 여러 Job의 진행률을 한 번에 집계할 때 쓴다(N+1 방지). */
    List<PackageItem> findByVersionNameIn(Collection<String> versionNames);

    void deleteByVersionName(String versionName);
}
