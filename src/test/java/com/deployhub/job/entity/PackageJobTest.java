package com.deployhub.job.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 상태 전이가 finished_at·deleted_at을 함께 맞추는지에 대한 회귀 방어. */
class PackageJobTest {

    /**
     * 정리된 Job을 재시도해 다시 돌리면 {@code deleted_at}이 남아 있으면 안 된다 — 남으면
     * 정리 배치의 {@code alive} 필터에서 영구히 빠지고(새 SharePoint 폴더가 회수 불가) FN-10이
     * 살아 있는 URL을 "만료됨"으로 표시한다. {@code resetForRerun}(force 재생성)만 비우고
     * {@code retry()}가 타는 이 경로를 빠뜨렸던 게 실제 결함이었다.
     */
    @Test
    void 비종료_상태로_전이하면_deletedAt이_비워진다() {
        PackageJob job = cleanedFailedJob();

        job.changeStatus(JobStatus.DOWNLOADING);

        assertThat(job.getDeletedAt()).isNull();
        assertThat(job.getFinishedAt()).isNull();
        assertThat(job.getStatus()).isEqualTo(JobStatus.DOWNLOADING);
    }

    @Test
    void 종료_상태로_전이하면_finishedAt을_찍는다() {
        PackageJob job = PackageJob.builder()
                .versionName("2027.01.01")
                .status(JobStatus.UPLOADING)
                .build();

        job.changeStatus(JobStatus.DONE);

        assertThat(job.getFinishedAt()).isNotNull();
        assertThat(job.getDeletedAt()).isNull();
    }

    /** force 재생성 경로도 같은 초기화를 보장해야 한다. */
    @Test
    void resetForRerun은_finishedAt과_deletedAt을_함께_비운다() {
        PackageJob job = cleanedFailedJob();

        job.resetForRerun();

        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getFinishedAt()).isNull();
        assertThat(job.getDeletedAt()).isNull();
    }

    /**
     * 정리는 status와 deleted_at을 함께 옮긴다 — 한쪽만 움직이면 매니페스트 잠금(status를 본다)과
     * 정리 배치의 alive 필터(deleted_at을 본다)가 같은 Job을 다르게 판정한다.
     */
    @Test
    void markDeleted는_status와_deletedAt을_함께_찍는다() {
        PackageJob job = cleanedFailedJob();

        assertThat(job.getStatus()).isEqualTo(JobStatus.DELETED);
        assertThat(job.getDeletedAt()).isNotNull();
        // 정리했으면 매니페스트를 고칠 수 있어야 한다 — 아니면 재패키징만 되고 수정은 막힌다.
        assertThat(job.blocksManifestModification()).isFalse();
        // 정리 시각이 종료 시각을 덮어쓰면 정리 배치의 보존 기한 계산이 어긋난다.
        assertThat(job.getFinishedAt()).isNotNull();
    }

    /** DELETED로 가는 문은 markDeleted 하나뿐이다 — changeStatus로 오면 finishedAt이 덮어써진다. */
    @Test
    void changeStatus로는_DELETED로_전이할_수_없다() {
        PackageJob job = PackageJob.builder().versionName("2027.01.01").status(JobStatus.DONE).build();

        assertThatThrownBy(() -> job.changeStatus(JobStatus.DELETED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 보존 기한이 지나 정리 배치가 폴더를 지운 뒤의 Job(정리 전에는 FAILED였다). */
    private PackageJob cleanedFailedJob() {
        PackageJob job = PackageJob.builder()
                .versionName("2027.01.01")
                .status(JobStatus.FAILED)
                .build();
        job.changeStatus(JobStatus.FAILED);
        job.markDeleted();
        assertThat(job.getDeletedAt()).isNotNull();
        return job;
    }
}
