package com.cachecraft;

import com.cachecraft.cache.ItemCache;
import com.cachecraft.model.CacheResponse;
import com.cachecraft.repository.ItemRepository;
import com.cachecraft.service.ItemService;
import com.cachecraft.service.LocalLockService;
import com.cachecraft.service.RedisLockService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CacheFlowIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("cachecraft")
                    .withUsername("cachecraft")
                    .withPassword("cachecraft");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void registerInfrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    @Autowired
    private ItemService itemService;

    @Autowired
    private ItemCache itemCache;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private HikariDataSource dataSource;

    @BeforeEach
    void clearRedisBetweenExperiments() {
        itemCache.clearItems();
    }

    @Test
    void cacheAsideMissQueriesPostgresThenNextRequestIsHit() {
        long before = itemRepository.getDbQueryCount();

        CacheResponse miss = itemService.getItem(42, "cache-aside", 30, 0, 0.10);
        CacheResponse hit = itemService.getItem(42, "cache-aside", 30, 0, 0.10);

        assertEquals(42, miss.item().id());
        assertTrue(!miss.cacheHit());
        assertTrue(hit.cacheHit());
        assertEquals(before + 1, itemRepository.getDbQueryCount());
    }

    @Test
    void jitteredCacheTtlStaysWithinConfiguredRange() {
        itemService.getItem(43, "cache-aside-jitter", 30, 0, 0.10);

        Long ttlMillis = redisTemplate.getExpire("item:43", TimeUnit.MILLISECONDS);
        assertNotNull(ttlMillis);
        assertTrue(ttlMillis >= 27_000 && ttlMillis <= 33_000,
                "actual TTL was %dms".formatted(ttlMillis));
    }

    @Test
    void localSingleFlightSendsOneSameKeyMissToPostgres() throws Exception {
        int requestCount = 16;
        long before = itemRepository.getDbQueryCount();
        ExecutorService requests = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CacheResponse>> results = new ArrayList<>();

        try {
            for (int index = 0; index < requestCount; index++) {
                results.add(requests.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return itemService.getItem(44, "local-single-flight", 30, 150, 0.10);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int cacheMisses = 0;
            int cacheHits = 0;
            for (Future<CacheResponse> result : results) {
                if (result.get(10, TimeUnit.SECONDS).cacheHit()) {
                    cacheHits++;
                } else {
                    cacheMisses++;
                }
            }

            assertEquals(1, itemRepository.getDbQueryCount() - before);
            assertEquals(1, cacheMisses);
            assertEquals(requestCount - 1, cacheHits);
        } finally {
            requests.shutdownNow();
        }
    }

    @Test
    void distributedLockCoalescesMissesAcrossIndependentServiceInstances() throws Exception {
        int requestCount = 16;
        long before = itemRepository.getDbQueryCount();
        ItemService firstInstance = new ItemService(
                itemRepository, itemCache, new LocalLockService(), new RedisLockService(redisTemplate, 5_000));
        ItemService secondInstance = new ItemService(
                itemRepository, itemCache, new LocalLockService(), new RedisLockService(redisTemplate, 5_000));
        ExecutorService requests = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CacheResponse>> results = new ArrayList<>();

        try {
            for (int index = 0; index < requestCount; index++) {
                ItemService instance = index % 2 == 0 ? firstInstance : secondInstance;
                results.add(requests.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return instance.getItem(46, "distributed-lock", 30, 150, 0.10);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int cacheMisses = 0;
            int cacheHits = 0;
            for (Future<CacheResponse> result : results) {
                if (result.get(10, TimeUnit.SECONDS).cacheHit()) {
                    cacheHits++;
                } else {
                    cacheMisses++;
                }
            }

            assertEquals(1, itemRepository.getDbQueryCount() - before);
            assertEquals(1, cacheMisses);
            assertEquals(requestCount - 1, cacheHits);
        } finally {
            requests.shutdownNow();
        }
    }

    @Test
    void postgresDelayRunsWhileHikariConnectionIsBorrowed() throws Exception {
        ExecutorService queryThread = Executors.newSingleThreadExecutor();
        long before = itemRepository.getDbQueryCount();
        try {
            Future<?> query = queryThread.submit(() -> itemRepository.findById(45, 500));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int activeConnections = 0;
            while (System.nanoTime() < deadline && activeConnections == 0) {
                activeConnections = dataSource.getHikariPoolMXBean().getActiveConnections();
                Thread.sleep(10);
            }

            assertTrue(activeConnections > 0, "query should hold a Hikari connection during pg_sleep");
            query.get(10, TimeUnit.SECONDS);
            assertEquals(before + 1, itemRepository.getDbQueryCount());
        } finally {
            queryThread.shutdownNow();
        }
    }

    /**
     * All bulk-warmed keys must share one absolute expiry timestamp. The PXAT
     * pipeline write uses one calculated timestamp so a slow write loop cannot
     * introduce accidental per-key expiry jitter.
     */
    @Test
    void bulkWarmWritesAllKeysWithOneSharedAbsoluteExpiry() {
        long ttlSeconds = 30;
        long beforeMs = System.currentTimeMillis();
        List<com.cachecraft.model.Item> items = itemRepository.findAll();
        long expiresAtEpochMs = Math.addExact(System.currentTimeMillis(), Math.multiplyExact(ttlSeconds, 1_000L));
        itemCache.putAllWithAbsoluteExpiry(items, expiresAtEpochMs);
        long afterMs = System.currentTimeMillis();

        // Every item key must be present and share the same expiry within the
        // wall-clock margin introduced by the test itself (not by the write loop).
        long firstExpiry = -1;
        int checkedKeys = 0;
        for (com.cachecraft.model.Item item : items) {
            Long ttlMillis = redisTemplate.getExpire("item:" + item.id(), TimeUnit.MILLISECONDS);
            assertNotNull(ttlMillis, "item:" + item.id() + " must be present after bulk warm");
            assertTrue(ttlMillis > 0, "item:" + item.id() + " must not be immediately expired");

            // Reconstruct the absolute expiry from the TTL snapshot.
            long observedExpiry = System.currentTimeMillis() + ttlMillis;
            if (firstExpiry == -1) {
                firstExpiry = observedExpiry;
            }
            // All keys share the same PXAT epoch; allow a 2-second reading spread.
            assertTrue(Math.abs(observedExpiry - firstExpiry) < 2_000,
                    "item:" + item.id() + " expiry drifted by more than 2 s from the first observed expiry");

            checkedKeys++;
            if (checkedKeys >= 50) {
                // Spot-check the first 50 items to avoid a slow full scan in CI.
                break;
            }
        }

        // Sanity check: the calculated epoch is in the expected future window.
        assertTrue(expiresAtEpochMs >= beforeMs + ttlSeconds * 1_000);
        assertTrue(expiresAtEpochMs <= afterMs + ttlSeconds * 1_000);
        assertEquals(10_000, items.size(), "seed set must contain exactly 10,000 items");
    }
}
