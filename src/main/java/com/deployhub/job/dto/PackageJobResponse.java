package com.deployhub.job.dto;

import com.deployhub.job.entity.PackageItem;
import com.deployhub.job.entity.PackageItemStatus;
import com.deployhub.job.entity.PackageJob;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

/** Job 요약. 응답을 가볍게 유지하려 진행률만 담고 항목 배열은 {@link PackageJobDetailResponse}가 싣는다. */
@Builder
public record PackageJobResponse(
        String versionName,
        String status,
        int totalItems,
        int completedItems,
        int progress,
        String spFolderUrl,
        Instant createdAt,
        Instant finishedAt,
        // 정리된 Job인지 구분할 유일한 수단이다 — 없으면 폴더가 지워진 Job도 DONE으로만 보인다.
        Instant deletedAt) {

    public static PackageJobResponse of(PackageJob job, List<PackageItem> items) {
        int total = items.size();
        long uploaded = items.stream()
                .filter(item -> item.getStatus() == PackageItemStatus.UPLOADED)
                .count();
        // 항목마다 다운로드·업로드 2단계로 센다 — DOWNLOADED를 완료로 세면 업로드가 도는 내내
        // 100%로 굳는다(2026-08-21 실측: 16GB 업로드 20분간 100% 표시).
        long steps = items.stream()
                .mapToLong(item -> switch (item.getStatus()) {
                    case UPLOADED -> 2L;
                    case DOWNLOADED -> 1L;
                    // 업로드 단계에서 죽었으면 다운로드 몫은 지킨다 — 아니면 실패가 쌓일수록 역주행한다.
                    case FAILED -> item.isFailedAfterDownload() ? 1L : 0L;
                    default -> 0L;
                })
                .sum();

        return PackageJobResponse.builder()
                .versionName(job.getVersionName())
                .status(job.getStatus().name())
                .totalItems(total)
                .completedItems((int) uploaded)
                .progress(total == 0 ? 0 : (int) (steps * 100 / (total * 2L)))
                .spFolderUrl(job.getSpFolderUrl())
                .createdAt(job.getCreatedAt())
                .finishedAt(job.getFinishedAt())
                .deletedAt(job.getDeletedAt())
                .build();
    }
}
