package com.cachecraft.service;

import com.cachecraft.cache.ItemCache;
import com.cachecraft.model.CacheResponse;
import com.cachecraft.model.Item;
import com.cachecraft.repository.ItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemServiceTest {

    private final ItemRepository repository = mock(ItemRepository.class);
    private final ItemCache itemCache = mock(ItemCache.class);
    private final LocalLockService localLockService = new LocalLockService();
    private final RedisLockService redisLockService = mock(RedisLockService.class);
    private final ItemService service = new ItemService(repository, itemCache, localLockService, redisLockService);
    private final Item item = new Item(42, "Item 42", 1654, "description");

    @BeforeEach
    void defaultCacheToMiss() {
        when(itemCache.findById(42)).thenReturn(Optional.empty());
        when(repository.findById(42, 25)).thenReturn(Optional.of(item));
    }

    @Test
    void noCacheAlwaysLoadsFromRepositoryAndNeverTouchesRedis() {
        CacheResponse response = service.getItem(42, "no-cache", 30, 25, 0.10);

        assertEquals(new CacheResponse(item, false), response);
        verify(repository).findById(42, 25);
        verify(itemCache, never()).findById(42);
        verify(itemCache, never()).put(eq(item), eq(30L), anyDouble());
    }

    @Test
    void cacheAsideReturnsHitWithoutQueryingRepository() {
        when(itemCache.findById(42)).thenReturn(Optional.of(item));

        CacheResponse response = service.getItem(42, "cache-aside", 30, 25, 0.10);

        assertTrue(response.cacheHit());
        assertEquals(item, response.item());
        verify(repository, never()).findById(42, 25);
    }

    @Test
    void cacheAsideMissLoadsAndStoresWithBaseTtl() {
        CacheResponse response = service.getItem(42, "cache-aside", 45, 25, 0.10);

        assertFalse(response.cacheHit());
        verify(itemCache).put(item, 45, 0.0);
    }

    @Test
    void jitteredCacheAsidePassesRequestedJitterToCache() {
        service.getItem(42, "cache-aside-jitter", 30, 25, 0.20);

        verify(itemCache).put(item, 30, 0.20);
    }

    @Test
    void localSingleFlightChecksRedisAgainAfterTakingLock() {
        when(itemCache.findById(42)).thenReturn(Optional.empty(), Optional.of(item));

        CacheResponse response = service.getItem(42, "local-single-flight", 30, 25, 0.10);

        assertTrue(response.cacheHit());
        verify(itemCache, org.mockito.Mockito.times(2)).findById(42);
        verify(repository, never()).findById(42, 25);
    }

    @Test
    void localSingleFlightCoalescesConcurrentMissesForSameKey() throws Exception {
        int requestCount = 12;
        AtomicReference<Item> cachedItem = new AtomicReference<>();
        AtomicInteger repositoryQueries = new AtomicInteger();
        CountDownLatch initialReads = new CountDownLatch(requestCount);
        ThreadLocal<Boolean> firstLookup = ThreadLocal.withInitial(() -> true);
        when(itemCache.findById(42)).thenAnswer(invocation -> {
            boolean isInitialLookup = firstLookup.get();
            firstLookup.set(false);
            Item observed = cachedItem.get();
            if (isInitialLookup) {
                initialReads.countDown();
                assertTrue(initialReads.await(5, TimeUnit.SECONDS));
            }
            return Optional.ofNullable(observed);
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            cachedItem.set(invocation.getArgument(0));
            return null;
        }).when(itemCache).put(eq(item), eq(30L), eq(0.0));
        when(repository.findById(42, 25)).thenAnswer(invocation -> {
            repositoryQueries.incrementAndGet();
            return Optional.of(item);
        });

        ExecutorService requests = Executors.newFixedThreadPool(requestCount);
        try {
            CountDownLatch ready = new CountDownLatch(requestCount);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<CacheResponse>> responses = new ArrayList<>();
            for (int index = 0; index < requestCount; index++) {
                responses.add(requests.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return service.getItem(42, "local-single-flight", 30, 25, 0.10);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int misses = 0;
            int hits = 0;
            for (Future<CacheResponse> response : responses) {
                if (response.get(10, TimeUnit.SECONDS).cacheHit()) {
                    hits++;
                } else {
                    misses++;
                }
            }
            assertEquals(1, repositoryQueries.get());
            assertEquals(1, misses);
            assertEquals(requestCount - 1, hits);
        } finally {
            requests.shutdownNow();
        }
    }

    @Test
    void missingItemIsReportedAsNotFound() {
        when(repository.findById(42, 25)).thenReturn(Optional.empty());

        assertThrows(ItemNotFoundException.class,
                () -> service.getItem(42, "no-cache", 30, 25, 0.10));
    }

    @Test
    void distributedLockRemainsUnsupportedUntilStageFour() {
        CacheResponse expected = new CacheResponse(item, false);
        when(redisLockService.getOrLoad(eq(42L), eq(25L), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(expected);

        assertEquals(expected, service.getItem(42, "distributed-lock", 30, 25, 0.10));
        verify(redisLockService).getOrLoad(eq(42L), eq(25L), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void distributedLockRejectsDelayAtOneSecondBeforeTouchingRedis() {
        assertThrows(InvalidDistributedLockDelayException.class,
                () -> service.getItem(42, "distributed-lock", 30, 1_000, 0.10));
        verify(redisLockService, never()).getOrLoad(eq(42L), eq(1_000L),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
