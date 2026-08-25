package com.deployhub.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;

public final class Concurrency {

    private Concurrency() {}

    /**
     * 전건 제출 — 동시성은 실행기의 고정 풀이 정하고 큐가 슬라이딩 윈도우다(배치로 나누면 느린
     * 항목이 다음 묶음을 막는다). {@code invokeAll}이 전건 완료 후 반환해 형제가 고아로 안 남는다.
     */
    public static <T, R> List<R> mapAll(ExecutorService executor, List<T> items, Function<T, R> mapper) {
        List<Callable<R>> tasks =
                items.stream().map(item -> (Callable<R>) () -> mapper.apply(item)).toList();
        List<R> results = new ArrayList<>(items.size());
        try {
            for (Future<R> future : executor.invokeAll(tasks)) {
                results.add(future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("병렬 처리 대기 중 인터럽트되었습니다.", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException cause
                    ? cause
                    : new IllegalStateException("병렬 처리 중 실패했습니다.", e.getCause());
        }
        return results;
    }
}
