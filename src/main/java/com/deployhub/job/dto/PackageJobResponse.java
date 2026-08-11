package com.deployhub.job.dto;

import com.deployhub.job.entity.PackageItem;
import com.deployhub.job.entity.PackageItemStatus;
import com.deployhub.job.entity.PackageJob;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
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
        String createdBy,
        Instant createdAt,
        Instant finishedAt) {

    private static final Set<PackageItemStatus> COMPLETED_STATUSES =
            EnumSet.of(PackageItemStatus.DOWNLOADED, PackageItemStatus.UPLOADED);

    public static PackageJobResponse of(PackageJob job, List<PackageItem> items) {
        int total = items.size();
        long completed = items.stream()
                .filter(item -> COMPLETED_STATUSES.contains(item.getStatus()))
                .count();

        return PackageJobResponse.builder()
                .versionName(job.getVersionName())
                .status(job.getStatus().name())
                .totalItems(total)
                .completedItems((int) completed)
                .progress(total == 0 ? 0 : (int) (completed * 100 / total))
                .spFolderUrl(job.getSpFolderUrl())
                .createdBy(job.getCreatedBy())
                .createdAt(job.getCreatedAt())
                .finishedAt(job.getFinishedAt())
                .build();
    }
}
