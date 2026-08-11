package com.deployhub.job.service;

import com.deployhub.job.entity.PackageItem;
import com.deployhub.job.repository.PackageItemRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * 항목 하나를 FAILED로 기록한다. 다운로드·업로드 단계가 같은 규칙을 공유해야 해서 한 곳에 둔다.
 *
 * <p>{@code dbMessage}에 서버 경로·호스트·업스트림 응답 본문 같은 인프라 정보를 넣지 말 것 —
 * 이 값이 무인증 {@code GET /api/package-jobs/{versionName}} 응답에 그대로 실린다.
 * 상세는 {@code detail}로 넘겨 로그에만 남긴다.
 */
@Slf4j
public final class PackageItemFailure {

    private PackageItemFailure() {}

    /** @return 항상 {@code false} — 호출자의 "이 항목 실패" 반환값으로 바로 쓰인다. */
    public static boolean fail(PackageItemRepository repository, PackageItem item, String dbMessage, String detail) {
        log.warn(
                "항목 실패: versionName={}, imageTag={}, reason={}, detail={}",
                item.getVersionName(),
                item.getImageTag(),
                dbMessage,
                detail);
        item.markFailed(dbMessage);
        repository.save(item);
        return false;
    }
}
