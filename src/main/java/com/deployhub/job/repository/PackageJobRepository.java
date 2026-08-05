package com.deployhub.job.repository;

import com.deployhub.job.entity.JobStatus;
import com.deployhub.job.entity.PackageJob;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PackageJobRepository extends JpaRepository<PackageJob, String> {

    /**
     * 재사용(FAILED 재실행, force=true인 DONE 재생성) 경로 전용 — 이 경우는 INSERT가
     * 아니라 UPDATE라 PK 제약이 동시성을 막아주지 못한다. 존재하지 않는 PK에는 이 락을
     * 걸지 않는다 — MySQL InnoDB가 없는 행에 FOR UPDATE를 걸면 갭 락이 생겨, 두 트랜잭션이
     * 동시에 INSERT할 때 깔끔한 유니크 제약 오류 대신 데드락(1213)이 날 수 있다. 신규
     * 생성은 그냥 save 후 제약 위반 예외를 번역하는 쪽(E-1301)이 단순하고 확실하다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PackageJob> findByVersionName(String versionName);

    /** Job 목록 API의 상태 필터와 기동 시 고아 Job 정리가 함께 쓴다. */
    List<PackageJob> findByStatusInOrderByCreatedAtDesc(Collection<JobStatus> statuses);
}
