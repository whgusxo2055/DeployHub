package com.deployhub.job.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.deployhub.job.entity.JobStatus;
import com.deployhub.job.entity.PackageItem;
import com.deployhub.job.entity.PackageItemStatus;
import com.deployhub.job.entity.PackageJob;
import java.util.List;
import org.junit.jupiter.api.Test;

class PackageJobResponseTest {

    private final PackageJob job = PackageJob.builder()
            .versionName("2026.09.01")
            .build();

    /** 다운로드를 완료로 세면 업로드가 도는 내내 100%로 굳는다 — 항목당 2단계로 나눈다. */
    @Test
    void 다운로드만_끝났으면_절반이다() {
        List<PackageItem> items = List.of(item(PackageItemStatus.DOWNLOADED), item(PackageItemStatus.DOWNLOADED));

        PackageJobResponse response = PackageJobResponse.of(job, items);

        assertThat(response.progress()).isEqualTo(50);
        // 아직 하나도 고객사로 나가지 않았다 — 여기서 2/2가 뜨면 "50%"와 나란히 모순된다.
        assertThat(response.completedItems()).isZero();
    }

    @Test
    void 업로드까지_끝나야_100이_된다() {
        List<PackageItem> items = List.of(item(PackageItemStatus.UPLOADED), item(PackageItemStatus.UPLOADED));

        PackageJobResponse response = PackageJobResponse.of(job, items);

        assertThat(response.progress()).isEqualTo(100);
        assertThat(response.completedItems()).isEqualTo(2);
    }

    /**
     * 2026-08-21 실측: 업로드가 하나씩 실패하며 진행률이 100→88→…→0으로 역주행했다.
     * 업로드 단계 실패는 다운로드 몫을 잃지 않아야 한다.
     */
    @Test
    void 업로드_단계_실패는_진행률을_되돌리지_않는다() {
        int beforeFailure = PackageJobResponse.of(
                        job, List.of(item(PackageItemStatus.DOWNLOADED), item(PackageItemStatus.DOWNLOADED)))
                .progress();

        PackageJobResponse afterFailure = PackageJobResponse.of(
                job, List.of(failedAfterDownload(), failedAfterDownload()));

        assertThat(afterFailure.progress()).isEqualTo(beforeFailure);
    }

    /** 다운로드 단계에서 죽은 항목은 fileSize가 없다 — 단계를 하나도 통과하지 않았다. */
    @Test
    void 다운로드_단계_실패는_0단계로_센다() {
        PackageJobResponse response = PackageJobResponse.of(
                job, List.of(item(PackageItemStatus.FAILED), item(PackageItemStatus.DOWNLOADED)));

        assertThat(response.progress()).isEqualTo(25);
    }

    @Test
    void 항목이_0건이면_0으로_나누지_않고_0을_반환한다() {
        PackageJobResponse response = PackageJobResponse.of(job, List.of());

        assertThat(response.totalItems()).isZero();
        assertThat(response.completedItems()).isZero();
        assertThat(response.progress()).isZero();
    }

    @Test
    void job_상태와_메타데이터를_그대로_옮긴다() {
        PackageJob doneJob = PackageJob.builder()
                .versionName("2026.09.01")
                .status(JobStatus.DONE)
                .spFolderUrl("https://contoso.sharepoint.com/folder")
                .build();

        PackageJobResponse response = PackageJobResponse.of(doneJob, List.of(item(PackageItemStatus.UPLOADED)));

        assertThat(response.versionName()).isEqualTo("2026.09.01");
        assertThat(response.status()).isEqualTo("DONE");
        assertThat(response.spFolderUrl()).isEqualTo("https://contoso.sharepoint.com/folder");
        assertThat(response.progress()).isEqualTo(100);
    }

    /** 다운로드는 끝났고 업로드에서 죽은 항목 — fileSize가 남아 있는 게 다운로드 실패와의 차이다. */
    private PackageItem failedAfterDownload() {
        return PackageItem.builder()
                .versionName("2026.09.01")
                .imageTag("pips:1.0.0")
                .status(PackageItemStatus.FAILED)
                .fileSize(1024L)
                .build();
    }

    private PackageItem item(PackageItemStatus status) {
        return PackageItem.builder()
                .versionName("2026.09.01")
                .imageTag("pips:1.0.0")
                .status(status)
                .build();
    }
}
