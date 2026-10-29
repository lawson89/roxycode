package org.roxycode.app.util;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class AsyncRefreshCache<T> {

    private final T emptyValue;
    private final long ttlMillis;
    private volatile T value;
    private volatile long timestampMs;
    private CompletableFuture<T> inFlightRefresh;

    public AsyncRefreshCache(T emptyValue) {
        this(emptyValue, 0L);
    }

    public AsyncRefreshCache(T emptyValue, long ttlMillis) {
        this.emptyValue = emptyValue;
        this.ttlMillis = ttlMillis;
        this.value = emptyValue;
    }

    public T get() {
        return value;
    }

    public synchronized void set(T newValue) {
        this.value = newValue;
        this.timestampMs = System.currentTimeMillis();
    }

    @SuppressWarnings("PMD.NullAssignment")
    public synchronized void clear() {
        this.value = emptyValue;
        this.timestampMs = 0L;
        this.inFlightRefresh = null;
    }

    public synchronized boolean isFresh() {
        return ttlMillis > 0 && timestampMs > 0 && System.currentTimeMillis() - timestampMs < ttlMillis;
    }

    @SuppressWarnings({"PMD.CompareObjectsWithEquals", "PMD.NullAssignment"})
    public synchronized CompletableFuture<T> refreshAsync(boolean force, Supplier<CompletableFuture<T>> loader) {
        if (!force && isFresh()) {
            return CompletableFuture.completedFuture(value);
        }
        if (inFlightRefresh != null && !inFlightRefresh.isDone()) {
            return inFlightRefresh;
        }
        CompletableFuture<T> future = loader.get();
        inFlightRefresh = future;
        future.whenComplete((res, ex) -> {
            synchronized (this) {
                if (inFlightRefresh == future) {
                    inFlightRefresh = null;
                }
            }
        });
        return future;
    }
}
