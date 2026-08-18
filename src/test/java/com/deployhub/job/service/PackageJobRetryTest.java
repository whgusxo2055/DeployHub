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
import com.deployhub.version.service.VersionComparisonService;
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
 * FAILED Job의 복구 경로. 두 케이스 모두 "항목 상태만 보고 판단하면 Job이 영구 좌초한다"는
 * 같은 결함의 양면이다 — 항목은 멀쩡한데 Job 단위로 실패한 경우와, 항목만 실패한 채 Job이
 * 끝나버리는 경우.
 */
@ExtendWith(MockitoExtension.class)
class PackageJobRetryTest {

    private static final String VERSION_NAME = "2026.08.18";

    @Mock
    private PackageJobRepository packageJobRepository;

    @Mock
    private PackageItemRepository packageItemRepository;

    @Mock
    private MainVersionRepository mainVersionRepository;

    @Mock
    private ComponentRepository componentRepository;

    @Mock
    private VersionComparisonService versionComparisonService;

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

        assertThatCode(() -> service().retry(VERSION_NAME, new PackageItemRetryRequest(List.of(), false)))
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

    private PackageJobService service() {
        return new PackageJobService(
                packageJobRepository,
                packageItemRepository,
                mainVersionRepository,
                componentRepository,
                versionComparisonService,
                packagingEligibilityService,
                workDir.toString(),
                0L);
    }

    private static PackageJob newJob(JobStatus status) {
        return PackageJob.builder()
                .versionName(VERSION_NAME)
                .status(status)
                .createdBy("tester")
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
