package com.deployhub.version.repository;

import com.deployhub.version.entity.MainVersion;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MainVersionRepository extends JpaRepository<MainVersion, String> {

    /**
     * 메인버전 행에 비관적 락. "컴포넌트 수정"과 "매니페스트 확정"이 <b>같은 행</b>을 잡아 서로를
     * 앞지르지 못하게 하는 용도다 — 잠금 대상은 이 행 하나이고, 보호하는 것은 그 아래 서브버전·
     * 컴포넌트·package_item 전체다. 상태를 보고 갱신하는 경로는 반드시 이쪽을 쓸 것.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MainVersion m WHERE m.versionName = :versionName")
    Optional<MainVersion> lockByVersionName(@Param("versionName") String versionName);

    /**
     * 목록 조회. 정렬은 {@code version_name}이 아니라 {@code sort_key}로 한다 —
     * index가 두 자리가 되면 문자열 비교가 뒤집힌다({@link MainVersion#sortKeyOf} 참고).
     *
     * <p>쿼리에 {@code ORDER BY}가 박혀 있어 {@code Pageable}의 {@code sort}는 뒤에 덧붙기만 한다
     * (사실상 무시된다). 정렬 기준을 노출할 계획이 없어 의도적으로 고정해 둔다.
     */
    @Query("""
            SELECT m FROM MainVersion m
            WHERE :keyword IS NULL OR m.versionName LIKE CONCAT('%', :keyword, '%')
            ORDER BY m.sortKey DESC
            """)
    Page<MainVersion> search(@Param("keyword") String keyword, Pageable pageable);

    /**
     * "직전 메인버전" — 변경 여부 판정의 기준이다.
     *
     * <p><b>서브버전이 하나라도 있는 것</b> 중에서 고른다. 다음 배포를 미리 만들어 두는 운영에서
     * 빈 껍데기가 직전으로 잡히면 전건이 "변경됨"으로 뒤집혀 패키징 대상이 통째로 잘못 계산된다.
     */
    @Query("""
            SELECT m FROM MainVersion m
            WHERE m.sortKey < :sortKey
              AND EXISTS (SELECT 1 FROM SubVersion s WHERE s.mainVersionName = m.versionName)
            ORDER BY m.sortKey DESC
            LIMIT 1
            """)
    Optional<MainVersion> findPrevious(@Param("sortKey") String sortKey);
}
