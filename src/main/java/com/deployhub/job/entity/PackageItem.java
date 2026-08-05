package com.deployhub.job.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ERD package_item — Job 내 산출물별 처리 상태 (PK = version_name, image_tag).
 *
 * <p>Phase 3이 확정 시점 스냅샷으로 생성한다(구현계획서 410행). 상태 갱신은 Phase 4~5가 채운다.
 */
@Entity
@Table(name = "package_item")
@IdClass(PackageItemId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PackageItem {

    @Id
    @Column(name = "version_name", length = 20)
    private String versionName;

    @Id
    @Column(name = "image_tag", length = 200)
    private String imageTag;

    @Column(name = "file_size")
    private Long fileSize;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private PackageItemStatus status = PackageItemStatus.PENDING;

    @Builder.Default
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    // ponytail: 지금은 항상 null이지만 Phase 4가 skopeo/NCR 실패 stderr를 여기 채운다.
    // NcrRegistryClient.credentialsForCli()가 반환하는 accessKey:secretKey가 skopeo
    // 실패 메시지에 그대로 노출될 수 있으므로(프로젝트 규약: 자격증명은 로그·API 응답
    // 어디에도 남기지 않는다 — NcrProperties/GraphProperties의 toString() 마스킹 패턴
    // 참고), Phase 4는 이 컬럼에 쓰기 전에 마스킹해야 한다. 응답 시점 마스킹은 늦다 —
    // DB에 평문이 이미 남는다.
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "file_url", length = 500)
    private String fileUrl;
}
