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
            .createdBy("tester")
            .build();

    @Test
    void DOWNLOADED와_UPLOADED만_완료로_집계한다() {
        List<PackageItem> items = List.of(
                item(PackageItemStatus.UPLOADED),
                item(PackageItemStatus.DOWNLOADED),
                item(PackageItemStatus.PENDING),
                item(PackageItemStatus.FAILED));

        PackageJobResponse response = PackageJobResponse.of(job, items);

        assertThat(response.totalItems()).isEqualTo(4);
        assertThat(response.completedItems()).isEqualTo(2);
        assertThat(response.progress()).isEqualTo(50);
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
                .createdBy("tester")
                .spFolderUrl("https://contoso.sharepoint.com/folder")
                .build();

        PackageJobResponse response = PackageJobResponse.of(doneJob, List.of(item(PackageItemStatus.UPLOADED)));

        assertThat(response.versionName()).isEqualTo("2026.09.01");
        assertThat(response.status()).isEqualTo("DONE");
        assertThat(response.spFolderUrl()).isEqualTo("https://contoso.sharepoint.com/folder");
        assertThat(response.progress()).isEqualTo(100);
    }

    private PackageItem item(PackageItemStatus status) {
        return PackageItem.builder()
                .versionName("2026.09.01")
                .imageTag("pips:1.0.0")
                .status(status)
                .build();
    }
}
