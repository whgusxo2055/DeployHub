package com.deployhub.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * 목록을 {@code batchSize}씩 나눠 배치 단위로 동시 처리한다(다음 배치는 이전 배치 완료 후 시작).
 * ponytail: 슬라이딩 윈도우가 아니라 배치라 가장 느린 항목이 다음 배치를 지연시킨다 —
 * 항목당 처리 시간이 비슷한 이 용도에서는 감수할 만하다.
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
            // 첫 실패에서 바로 던지면 나머지 future가 join되지 않은 채 계속 돈다 —
            // 외부 프로세스가 고아로 남아 정리 중인 파일에 계속 쓴다. 전부 join한 뒤 첫 실패를 던진다.
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
