package com.deployhub.job.service;

import com.deployhub.common.ApiException;
import com.deployhub.common.ErrorCode;
import com.deployhub.job.dto.PackageFileResponse;
import com.deployhub.job.dto.PackageFilesResponse;
import com.deployhub.job.dto.PackageJobResponse;
import com.deployhub.job.entity.JobStatus;
import com.deployhub.job.entity.PackageItem;
import com.deployhub.job.entity.PackageJob;
import com.deployhub.job.repository.PackageItemRepository;
import com.deployhub.job.repository.PackageJobRepository;
import com.deployhub.registry.ImageReference;
import com.deployhub.version.entity.Component;
import com.deployhub.version.entity.SubVersion;
import com.deployhub.version.repository.ComponentRepository;
import com.deployhub.version.repository.SubVersionRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 업로드 파일 URL 목록 제공. 조회 전용이라 {@link PackageJobService}(생성·상태 전이)와 분리했다. */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PackageFileService {

    private final PackageJobRepository packageJobRepository;
    private final PackageItemRepository packageItemRepository;
    private final SubVersionRepository subVersionRepository;
    private final ComponentRepository componentRepository;

    /**
     * {@code DONE}이 아니면 목록 대신 진행 상태를 알린다 — 오류 응답 {@code details}에 상태와 진행률을 실어
     * 호출측이 폴링 엔드포인트를 한 번 더 부르지 않아도 되게 한다.
     */
    public PackageFilesResponse listFiles(String versionName) {
        PackageJob job = packageJobRepository.getOrThrow(versionName);
        List<PackageItem> items = packageItemRepository.findByVersionNameOrderByImageTagAsc(versionName);

        if (job.getStatus() != JobStatus.DONE) {
            PackageJobResponse progress = PackageJobResponse.of(job, items);
            throw new ApiException(
                    ErrorCode.PACKAGE_NOT_READY,
                    List.of("status=" + progress.status(), "progress=" + progress.progress() + "%"));
        }

        Map<String, SubVersion> subVersionByTag = subVersionByImageTag(versionName);
        List<PackageFileResponse> files = items.stream()
                .map(item -> toFileResponse(item, subVersionByTag.get(item.getImageTag())))
                .toList();

        return PackageFilesResponse.builder()
                .versionName(versionName)
                .folderUrl(job.getSpFolderUrl())
                .finishedAt(job.getFinishedAt())
                .deletedAt(job.getDeletedAt())
                .files(files)
                .build();
    }

    /** {@code image_tag} → {@code sub_version} 역참조. 항목마다 조회하지 않고 메인버전 단위로 두 번만 읽는다. */
    private Map<String, SubVersion> subVersionByImageTag(String mainVersionName) {
        Map<Long, SubVersion> byId = subVersionRepository.findByMainVersionNameOrderBySortOrderAsc(mainVersionName)
                .stream()
                .collect(Collectors.toMap(SubVersion::getId, Function.identity()));

        Map<String, SubVersion> byTag = new HashMap<>();
        for (Component component : componentRepository.findByMainVersionName(mainVersionName)) {
            SubVersion subVersion = byId.get(component.getSubVersionId());
            if (subVersion != null) {
                byTag.put(component.getImageTag(), subVersion);
            }
        }
        return byTag;
    }

    private PackageFileResponse toFileResponse(PackageItem item, SubVersion subVersion) {
        return PackageFileResponse.builder()
                .fileName(tarFileNameOrNull(item.getImageTag()))
                .fileSize(item.getFileSize())
                .imageTag(item.getImageTag())
                .subVersionCode(subVersion == null ? null : subVersion.getCode())
                .subVersionVersion(subVersion == null ? null : subVersion.getVersion())
                .fileUrl(item.getFileUrl())
                .build();
    }

    /** 확정 시점에 문법이 강제되지만, 그 이전에 저장된 행 하나 때문에 목록 조회 전체가 500이 되지 않게 한다. */
    private String tarFileNameOrNull(String imageTag) {
        try {
            return ImageReference.parse(imageTag).tarFileName();
        } catch (IllegalArgumentException e) {
            log.warn("image_tag 형식이 올바르지 않아 파일명을 산출할 수 없습니다: {}", imageTag);
            return null;
        }
    }
}
