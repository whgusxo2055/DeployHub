package com.deployhub.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * 목록을 {@code batchSize}씩 나눠 각 배치를 동시에 처리하고, 다음 배치는 이전 배치가
 * 끝난 뒤 시작한다(FN-05 5건 제한, FN-06-1 DOWNLOAD_CONCURRENCY 3건 제한 — 구현계획서
 * 456·469행). 슬라이딩 윈도우가 아니라 배치 단위인 이유는 단순함 때문이다 — 배치 안에서
 * 가장 느린 작업 하나가 다음 배치 시작을 지연시킬 수 있지만, 항목 하나당 처리 시간이
 * 비슷한 이 용도(매니페스트 조회·이미지 다운로드)에서는 감수할 만한 손해다.
 */
public final class BoundedParallelism {

    private BoundedParallelism() {}

    public static <T, R> List<R> mapInBatches(List<T> items, int batchSize, Executor executor, Function<T, R> mapper) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize는 1 이상이어야 합니다: " + batchSize);
        }
        List<R> results = new ArrayList<>(items.size());
        for (int start = 0; start < items.size(); start += batchSize) {
            List<T> batch = items.subList(start, Math.min(start + batchSize, items.size()));
            List<CompletableFuture<R>> futures = batch.stream()
                    .map(item -> CompletableFuture.supplyAsync(() -> mapper.apply(item), executor))
                    .toList();
            // 첫 실패에서 바로 던지면 같은 배치의 나머지 future는 join되지 않은 채 계속
            // 실행된다 — 외부 프로세스(skopeo)를 돌리는 작업이라면 호출자가 이미 "실패"로
            // 보고 정리(임시 파일 삭제 등)를 시작한 뒤에도 그 프로세스가 같은 파일에 계속
            // 쓸 수 있다. 배치 전체를 join한 뒤 첫 실패를 던진다.
            RuntimeException firstFailure = null;
            for (CompletableFuture<R> future : futures) {
                try {
                    results.add(future.join());
                } catch (CompletionException e) {
                    RuntimeException failure = e.getCause() instanceof RuntimeException runtimeException ? runtimeException : e;
                    if (firstFailure == null) {
                        firstFailure = failure;
                    }
                }
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
        }
        return results;
    }
}
