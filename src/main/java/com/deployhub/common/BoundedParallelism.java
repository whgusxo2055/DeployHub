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
            // 스트림으로 한 번에 제출하면 중간에 RejectedExecutionException이 나는 순간 futures가
            // 만들어지지 않아, 이미 돌기 시작한 작업을 join하지 못한 채 빠져나간다 — 아래 join 루프가
            // 막으려는 바로 그 상황이다. 제출을 루프로 돌려 거부 시점까지의 future를 확보한다.
            List<CompletableFuture<R>> futures = new ArrayList<>(batch.size());
            RuntimeException submitFailure = null;
            for (T item : batch) {
                try {
                    futures.add(CompletableFuture.supplyAsync(() -> mapper.apply(item), executor));
                } catch (RuntimeException e) {
                    submitFailure = e;
                    break;
                }
            }
            // 첫 실패에서 바로 던지면 나머지 future가 join되지 않은 채 계속 돈다 —
            // 외부 프로세스가 고아로 남아 정리 중인 파일에 계속 쓴다. 전부 join한 뒤 첫 실패를 던진다.
            RuntimeException firstFailure = null;
            for (CompletableFuture<R> future : futures) {
                try {
                    results.add(future.join());
                } catch (RuntimeException e) {
                    // CompletionException뿐 아니라 CancellationException도 여기로 온다 — 좁게 잡으면
                    // 취소된 배치에서 나머지를 join하지 않고 빠져나간다.
                    RuntimeException failure =
                            e instanceof CompletionException && e.getCause() instanceof RuntimeException cause ? cause : e;
                    if (firstFailure == null) {
                        firstFailure = failure;
                    }
                }
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
            if (submitFailure != null) {
                throw submitFailure; // 배치를 다 제출하지 못했으면 부분 결과를 성공으로 돌려주지 않는다
            }
        }
        return results;
    }
}
