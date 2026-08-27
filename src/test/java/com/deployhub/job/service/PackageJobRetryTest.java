package com.deployhub.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

import com.deployhub.job.dto.PackageItemRetryRequest;
import com.deployhub.job.entity.JobStatus;
import com.deployhub.job.entity.PackageItem;
import com.deployhub.job.entity.PackageItemStatus;
import com.deployhub.job.entity.PackageJob;
import com.deployhub.job.repository.PackageItemRepository;
import com.deployhub.job.repository.PackageJobRepository;
import com.deployhub.version.repository.ComponentRepository;
import com.deployhub.version.repository.MainVersionRepository;
import com.deployhub.version.service.PackagingEligibilityService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * FAILED Job의 복구 경로. 항목 상태만 보고 판단하면 Job이 영구 좌초하거나(FAILED 0건이라 거부,
 * 성급한 DONE) 멀쩡한 tar를 버리고 전건 재수집한다 — 그 경계들을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class PackageJobRetryTest {

    private static final String VERSION_NAME = "2026.08.18.001";

    @Mock
    private PackageJobRepository packageJobRepository;

    @Mock
    private PackageItemRepository packageItemRepository;

    @Mock
    private MainVersionRepository mainVersionRepository;

    @Mock
    private ComponentRepository componentRepository;

    @Mock
    private PackagingEligibilityService packagingEligibilityService;

    @TempDir
    Path workDir;

    /**
     * 폴더 확보 실패처럼 Job 단위로 죽으면 항목은 전부 DOWNLOADED인데 Job만 FAILED가 된다.
     * FAILED 항목이 0건이라고 재시도를 거부하면 그 Job은 다시 돌릴 방법이 영영 없다.
     */
    @Test
    void FAILED_항목이_없어도_태그_미지정_재시도는_단계를_재개시킨다() throws IOException {
        PackageJob job = newJob(JobStatus.FAILED);
        PackageItem downloaded = newItem("acme/a:1.0", PackageItemStatus.DOWNLOADED);
        Files.createDirectories(workDir.resolve(VERSION_NAME).resolve("images"));

        when(packageJobRepository.lockOrThrow(VERSION_NAME)).thenReturn(job);
        when(packageItemRepository.findByVersionNameOrderByImageTagAsc(VERSION_NAME))
                .thenReturn(List.of(downloaded));

        assertThatCode(() -> service().retry(VERSION_NAME, new PackageItemRetryRequest(List.of())))
                .doesNotThrowAnyException();

        assertThat(job.getStatus()).isEqualTo(JobStatus.DOWNLOADING);
        // 이미 받아 둔 항목은 되돌리지 않는다 — 되돌리면 재개가 전건 재수집이 된다.
        assertThat(downloaded.getStatus()).isEqualTo(PackageItemStatus.DOWNLOADED);
    }

    /**
     * 부분 재시도는 지정한 태그만 되돌리므로 나머지 FAILED는 다운로드·업로드 양쪽에서 스킵된 채
     * 마지막 전이에 도달한다. DONE으로 끝내면 재시도가 막히고 정리 배치가 tar를 지운다.
     */
    @Test
    void FAILED_항목이_남아_있으면_DONE_대신_FAILED로_끝낸다() {
        PackageJob job = newJob(JobStatus.UPLOADING);

        when(packageJobRepository.getOrThrow(VERSION_NAME)).thenReturn(job);
        when(packageItemRepository.findByVersionNameOrderByImageTagAsc(VERSION_NAME))
                .thenReturn(List.of(
                        newItem("acme/a:1.0", PackageItemStatus.UPLOADED),
                        newItem("acme/b:1.0", PackageItemStatus.FAILED)));

        service().finish(VERSION_NAME);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void 전_항목이_성공했으면_DONE으로_끝낸다() {
        PackageJob job = newJob(JobStatus.UPLOADING);

        when(packageJobRepository.getOrThrow(VERSION_NAME)).thenReturn(job);
        when(packageItemRepository.findByVersionNameOrderByImageTagAsc(VERSION_NAME))
                .thenReturn(List.of(newItem("acme/a:1.0", PackageItemStatus.UPLOADED)));

        service().finish(VERSION_NAME);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
    }

    @Test
    void 다운로드를_시작한_적_없는_Job은_작업_디렉터리가_없어도_재시도된다() {
        // images 디렉터리는 다운로드가 시작될 때 만들어진다 — VALIDATING에서 죽으면 애초에 없다.
        // 그 부재를 "소실"로 보면 실패 원인과 무관한 E-0703이 나간다.
        PackageJob job = PackageJob.builder().versionName(VERSION_NAME).status(JobStatus.FAILED).build();
        PackageItem failed = newItem("acme/a:1.0", PackageItemStatus.FAILED);
        when(packageJobRepository.lockOrThrow(VERSION_NAME)).thenReturn(job);
        when(packageItemRepository.findByVersionNameOrderByImageTagAsc(VERSION_NAME)).thenReturn(List.of(failed));

        service().retry(VERSION_NAME, new PackageItemRetryRequest(null));

        assertThat(job.getStatus()).isEqualTo(JobStatus.DOWNLOADING);
        assertThat(failed.getStatus()).isEqualTo(PackageItemStatus.PENDING);
    }

    /**
     * 업로드 단계에서 죽으면 항목은 FAILED가 되지만 tar는 그대로 남아 있다 — PENDING으로 되돌리면
     * 멀쩡한 아카이브를 버리고 처음부터 다시 받는다(2026-08-21 실측: 9건 16GB 재수집).
     */
    @Test
    void 업로드_단계에서_죽은_항목은_받아_둔_tar를_유지한_채_재시도한다() throws IOException {
        PackageJob job = newJob(JobStatus.FAILED);
        PackageItem uploadFailed = newFailedAfterDownload("acme/a:1.0", 7L);
        writeTar("acme_a_1.0.tar", 7);

        when(packageJobRepository.lockOrThrow(VERSION_NAME)).thenReturn(job);
        when(packageItemRepository.findByVersionNameOrderByImageTagAsc(VERSION_NAME))
                .thenReturn(List.of(uploadFailed));

        service().retry(VERSION_NAME, new PackageItemRetryRequest(null));

        assertThat(uploadFailed.getStatus()).isEqualTo(PackageItemStatus.DOWNLOADED);
        assertThat(uploadFailed.getFileSize()).isEqualTo(7L);
        // 실패 사유와 죽은 업로드 URL은 지워야 한다 — 재개 중인 항목이 옛 값을 그대로 노출한다.
        assertThat(uploadFailed.getErrorMessage()).isNull();
        assertThat(uploadFailed.getFileUrl()).isNull();
    }

    /** tar가 없거나 크기가 다르면 살릴 게 없다 — 업로드가 E-0604로 죽기 전에 다시 받아야 한다. */
    @Test
    void tar가_사라졌으면_업로드_단계_실패라도_다시_받는다() throws IOException {
        PackageJob job = newJob(JobStatus.FAILED);
        PackageItem uploadFailed = newFailedAfterDownload("acme/a:1.0", 7L);
        Files.createDirectories(workDir.resolve(VERSION_NAME).resolve("images"));

        when(packageJobRepository.lockOrThrow(VERSION_NAME)).thenReturn(job);
        when(packageItemRepository.findByVersionNameOrderByImageTagAsc(VERSION_NAME))
                .thenReturn(List.of(uploadFailed));

        service().retry(VERSION_NAME, new PackageItemRetryRequest(null));

        assertThat(uploadFailed.getStatus()).isEqualTo(PackageItemStatus.PENDING);
        assertThat(uploadFailed.getFileSize()).isNull();
    }

    /**
     * 한 요청 안에서 항목별로 갈린다 — 실제 출하한 동작이다. 크기 불일치 갈래가 특히 중요하다:
     * 중단된 다운로드가 남긴 잘린 tar를 완전한 것으로 올리는 걸 막는 유일한 장치다.
     */
    @Test
    void 재시도_대상은_tar_상태에_따라_항목별로_갈린다() throws IOException {
        PackageJob job = newJob(JobStatus.FAILED);
        PackageItem intact = newFailedAfterDownload("acme/a:1.0", 7L);
        PackageItem truncated = newFailedAfterDownload("acme/b:1.0", 7L);
        PackageItem downloadFailed = newItem("acme/c:1.0", PackageItemStatus.FAILED); // fileSize 없음
        writeTar("acme_a_1.0.tar", 7);
        writeTar("acme_b_1.0.tar", 6); // 중단된 다운로드가 남긴 잘린 tar

        when(packageJobRepository.lockOrThrow(VERSION_NAME)).thenReturn(job);
        when(packageItemRepository.findByVersionNameOrderByImageTagAsc(VERSION_NAME))
                .thenReturn(List.of(intact, truncated, downloadFailed));

        service().retry(VERSION_NAME, new PackageItemRetryRequest(null));

        assertThat(intact.getStatus()).isEqualTo(PackageItemStatus.DOWNLOADED);
        assertThat(truncated.getStatus()).isEqualTo(PackageItemStatus.PENDING);
        assertThat(downloadFailed.getStatus()).isEqualTo(PackageItemStatus.PENDING);
    }

    private void writeTar(String fileName, int size) throws IOException {
        Path images = workDir.resolve(VERSION_NAME).resolve("images");
        Files.createDirectories(images);
        Files.write(images.resolve(fileName), new byte[size]);
    }

    /** 다운로드는 끝났고 업로드에서 죽은 항목 — fileSize가 남아 있는 게 다운로드 실패와의 차이다. */
    private static PackageItem newFailedAfterDownload(String imageTag, long fileSize) {
        return PackageItem.builder()
                .versionName(VERSION_NAME)
                .imageTag(imageTag)
                .status(PackageItemStatus.FAILED)
                .fileSize(fileSize)
                .errorMessage("E-1101: SharePoint 업로드에 실패했습니다.")
                .fileUrl("https://example.invalid/old.tar")
                .build();
    }

    private PackageJobService service() {
        return new PackageJobService(
                packageJobRepository,
                packageItemRepository,
                mainVersionRepository,
                componentRepository,
                packagingEligibilityService,
                workDir.toString());
    }

    private static PackageJob newJob(JobStatus status) {
        return PackageJob.builder()
                .versionName(VERSION_NAME)
                .status(status)
                .build();
    }

    private static PackageItem newItem(String imageTag, PackageItemStatus status) {
        return PackageItem.builder()
                .versionName(VERSION_NAME)
                .imageTag(imageTag)
                .status(status)
                .build();
    }
}
