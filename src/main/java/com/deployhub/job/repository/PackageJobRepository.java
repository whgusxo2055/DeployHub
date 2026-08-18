package com.deployhub.job.repository;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
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
     * 재사용 경로 전용 — UPDATE라 PK 제약이 동시성을 막아주지 못한다.
     * 존재하지 않는 PK에는 걸지 말 것 — InnoDB가 갭 락을 잡아 동시 INSERT가 깔끔한 유니크 위반 대신
     * 데드락으로 터진다. 신규 생성은 save 후 제약 위반을 번역하는 쪽이 확실하다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PackageJob> findByVersionName(String versionName);

    /** 락 없는 조회. 상태를 보고 분기하거나 갱신할 거라면 {@link #lockOrThrow}를 쓸 것. */
    default PackageJob getOrThrow(String versionName) {
        return findById(versionName).orElseThrow(() -> notFound(versionName));
    }

    /**
     * 락을 쥔 조회. 상태 체크 후 갱신하는 경로는 반드시 이쪽이어야 한다 —
     * 락 없이 읽으면 동시 요청 두 건이 같은 상태를 보고 둘 다 통과한다.
     */
    default PackageJob lockOrThrow(String versionName) {
        return findByVersionName(versionName).orElseThrow(() -> notFound(versionName));
    }

    private static ApiException notFound(String versionName) {
        return new ApiException(ErrorCode.PACKAGE_JOB_NOT_FOUND, List.of("versionName=" + versionName));
    }

    /** Job 목록 API의 상태 필터와 기동 시 고아 Job 정리가 함께 쓴다. */
    List<PackageJob> findByStatusInOrderByCreatedAtDesc(Collection<JobStatus> statuses);

    /**
     * 보존·정리 배치용. 정렬이 곧 "최근 RETENTION_COUNT건 보호"의 기준이라 배치는 앞에서부터 세기만 하면 된다.
     * ponytail: 전건을 메모리로 가져와 자바에서 판정한다 — 메인버전당 Job이 1건이라 수십 행 규모다.
     * 결과는 스냅샷이므로 실제 삭제 전에 {@code PackagePurgeService}가 락을 잡고 재확인한다.
     */
    List<PackageJob> findByStatusInOrderByFinishedAtDesc(Collection<JobStatus> statuses);
}
