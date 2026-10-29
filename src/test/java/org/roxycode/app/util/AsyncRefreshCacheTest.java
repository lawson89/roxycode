package org.roxycode.app.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AsyncRefreshCacheTest {

    @Test
    void testGetSetClearAndTtlFreshness() throws InterruptedException {
        AsyncRefreshCache<List<String>> cache = new AsyncRefreshCache<>(List.of(), 80L);
        assertEquals(List.of(), cache.get());
        assertFalse(cache.isFresh());

        cache.set(List.of("a", "b"));
        assertEquals(List.of("a", "b"), cache.get());
        assertTrue(cache.isFresh());

        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<List<String>> hit = cache.refreshAsync(false, () -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(List.of("c"));
        });
        assertEquals(List.of("a", "b"), hit.join());
        assertEquals(0, calls.get());

        Thread.sleep(100L);
        assertFalse(cache.isFresh());

        CompletableFuture<List<String>> refreshed = cache.refreshAsync(false, () -> {
            calls.incrementAndGet();
            cache.set(List.of("c"));
            return CompletableFuture.completedFuture(List.of("c"));
        });
        assertEquals(List.of("c"), refreshed.join());
        assertEquals(1, calls.get());

        cache.clear();
        assertEquals(List.of(), cache.get());
        assertFalse(cache.isFresh());
    }

    @Test
    void testInFlightRefreshDeduplicationAndCompletionReset() {
        AsyncRefreshCache<String> cache = new AsyncRefreshCache<>("");
        CompletableFuture<String> pending = new CompletableFuture<>();
        AtomicInteger loaderCalls = new AtomicInteger();

        CompletableFuture<String> f1 = cache.refreshAsync(true, () -> {
            loaderCalls.incrementAndGet();
            return pending;
        });
        CompletableFuture<String> f2 = cache.refreshAsync(true, () -> {
            loaderCalls.incrementAndGet();
            return CompletableFuture.completedFuture("other");
        });

        assertSame(f1, f2);
        assertEquals(1, loaderCalls.get());

        pending.complete("done");
        assertEquals("done", f1.join());

        CompletableFuture<String> f3 = cache.refreshAsync(true, () -> {
            loaderCalls.incrementAndGet();
            return CompletableFuture.completedFuture("next");
        });
        assertEquals("next", f3.join());
        assertEquals(2, loaderCalls.get());
    }
}
