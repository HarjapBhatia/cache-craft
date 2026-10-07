package com.cachecraft.service;

import com.cachecraft.model.CacheResponse;
import com.cachecraft.model.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisLockServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisLockService lockService = new RedisLockService(redisTemplate, 5_000);
    private final Item item = new Item(42, "Item 42", 1654, "description");

    @BeforeEach
    void exposeValueOperations() {
        when(redisTemplate.opsForValue()).thenReturn(values);
    }

    @Test
    void leaderDoubleChecksCacheAndReleasesItsTokenSafely() {
        when(values.setIfAbsent(eq("lock:item:42"), anyString(), eq(Duration.ofMillis(5_000))))
                .thenReturn(true);
        AtomicInteger cacheReads = new AtomicInteger();
        Supplier<Optional<Item>> cacheLookup = () -> {
            cacheReads.incrementAndGet();
            return Optional.empty();
        };
        Supplier<Item> loader = () -> item;

        CacheResponse result = lockService.getOrLoad(42, 250, cacheLookup, loader);

        assertEquals(new CacheResponse(item, false), result);
        assertEquals(2, cacheReads.get(), "leader must recheck cache after acquiring the lock");
        verify(values).setIfAbsent(eq("lock:item:42"), anyString(), eq(Duration.ofMillis(5_000)));
        verifyTokenCheckedRelease("lock:item:42");
    }

    @Test
    void leaderReturnsHitWhenCacheWasFilledBeforeItAcquiredLock() {
        when(values.setIfAbsent(eq("lock:item:42"), anyString(), any(Duration.class))).thenReturn(true);
        AtomicInteger reads = new AtomicInteger();
        Supplier<Optional<Item>> cacheLookup = () ->
                reads.incrementAndGet() == 1 ? Optional.empty() : Optional.of(item);

        CacheResponse result = lockService.getOrLoad(42, 0, cacheLookup, () -> {
            throw new AssertionError("a late lock winner must not query the database");
        });

        assertEquals(new CacheResponse(item, true), result);
        verifyTokenCheckedRelease("lock:item:42");
    }

    @Test
    void followerReturnsCacheValueWithoutTryingDatabase() {
        when(values.setIfAbsent(eq("lock:item:42"), anyString(), any(Duration.class))).thenReturn(false);
        AtomicInteger reads = new AtomicInteger();
        Supplier<Optional<Item>> cacheLookup = () ->
                reads.incrementAndGet() == 1 ? Optional.empty() : Optional.of(item);
        Supplier<Item> loader = () -> {
            throw new AssertionError("followers must not query PostgreSQL");
        };

        CacheResponse result = lockService.getOrLoad(42, 0, cacheLookup, loader);

        assertEquals(new CacheResponse(item, true), result);
        verify(values, times(1)).setIfAbsent(eq("lock:item:42"), anyString(), any(Duration.class));
        verify(redisTemplate, never()).execute(any(RedisScript.class), any(List.class), any(Object.class));
    }

    @Test
    void followerRetriesAcquisitionOnceThenChecksCacheAgainAsLeader() {
        AtomicBoolean valueAppearsBeforeRetry = new AtomicBoolean(false);
        AtomicInteger acquisitions = new AtomicInteger();
        when(values.setIfAbsent(eq("lock:item:42"), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    if (acquisitions.incrementAndGet() == 1) {
                        return false;
                    }
                    valueAppearsBeforeRetry.set(true);
                    return true;
                });
        Supplier<Optional<Item>> cacheLookup = () -> valueAppearsBeforeRetry.get()
                ? Optional.of(item)
                : Optional.empty();

        CacheResponse result = lockService.getOrLoad(42, 0, cacheLookup,
                () -> { throw new AssertionError("cache should be found after retry acquisition"); });

        assertEquals(new CacheResponse(item, true), result);
        assertEquals(2, acquisitions.get());
        verifyTokenCheckedRelease("lock:item:42");
    }

    @Test
    void followerTimesOutWith503DomainExceptionWithoutDatabaseFallback() {
        when(values.setIfAbsent(eq("lock:item:42"), anyString(), any(Duration.class))).thenReturn(false);

        assertThrows(LockUnavailableException.class,
                () -> lockService.getOrLoad(42, 0, Optional::<Item>empty,
                        () -> { throw new AssertionError("timed-out follower must not query PostgreSQL"); }));

        verify(values, times(2)).setIfAbsent(eq("lock:item:42"), anyString(), any(Duration.class));
        verify(redisTemplate, never()).execute(any(RedisScript.class), any(List.class), any(Object.class));
    }

    @Test
    void lockIsReleasedWhenLeaderWorkThrows() {
        when(values.setIfAbsent(eq("lock:item:42"), anyString(), any(Duration.class))).thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> lockService.getOrLoad(42, 0, Optional::<Item>empty,
                        () -> { throw new IllegalStateException("database failure"); }));

        verifyTokenCheckedRelease("lock:item:42");
    }

    private void verifyTokenCheckedRelease(String expectedKey) {
        org.mockito.ArgumentCaptor<String> acquiredTokens = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values, org.mockito.Mockito.atLeastOnce())
                .setIfAbsent(eq(expectedKey), acquiredTokens.capture(), any(Duration.class));
        org.mockito.ArgumentCaptor<RedisScript> script = org.mockito.ArgumentCaptor.forClass(RedisScript.class);
        org.mockito.ArgumentCaptor<String> releasedToken = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).execute(script.capture(), eq(List.of(expectedKey)), releasedToken.capture());
        String lua = script.getValue().getScriptAsString();
        assertTrue(lua.contains("redis.call('GET', KEYS[1]) == ARGV[1]"));
        assertTrue(lua.contains("return redis.call('DEL', KEYS[1])"));
        assertTrue(acquiredTokens.getAllValues().contains(releasedToken.getValue()),
                "the release script must use the token created by this service instance");
        assertFalse(releasedToken.getValue().isBlank());
    }
}
