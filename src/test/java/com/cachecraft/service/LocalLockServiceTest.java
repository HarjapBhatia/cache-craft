package com.cachecraft.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalLockServiceTest {

    private final LocalLockService lockService = new LocalLockService();

    @Test
    void sameKeyOperationsNeverOverlap() throws Exception {
        int workerCount = 8;
        ExecutorService workers = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger activeOperations = new AtomicInteger();
        AtomicInteger maximumActiveOperations = new AtomicInteger();
        List<Future<?>> results = new ArrayList<>();

        try {
            for (int index = 0; index < workerCount; index++) {
                results.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    lockService.withLock(42, () -> {
                        int active = activeOperations.incrementAndGet();
                        maximumActiveOperations.accumulateAndGet(active, Math::max);
                        firstEntered.countDown();
                        try {
                            awaitLatch(release);
                        } finally {
                            activeOperations.decrementAndGet();
                        }
                        return null;
                    });
                    return null;
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            Thread.sleep(50);
            release.countDown();
            for (Future<?> result : results) {
                result.get(10, TimeUnit.SECONDS);
            }

            assertEquals(1, maximumActiveOperations.get());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void lockIsReleasedWhenOperationThrows() {
        assertThrows(IllegalStateException.class,
                () -> lockService.withLock(42, () -> { throw new IllegalStateException("expected"); }));

        assertEquals("completed", lockService.withLock(42, () -> "completed"));
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the lock test gate", exception);
        }
    }
}
