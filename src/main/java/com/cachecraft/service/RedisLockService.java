package com.cachecraft.service;

import com.cachecraft.model.CacheResponse;
import com.cachecraft.model.Item;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Coordinates cache-miss leaders and followers across API processes via Redis. */
@Service
public class RedisLockService {

    private static final String LOCK_KEY_PREFIX = "lock:item:";
    private static final String RELEASE_LOCK_LUA = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            new DefaultRedisScript<>(RELEASE_LOCK_LUA, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final long lockLeaseMillis;

    public RedisLockService(
            StringRedisTemplate redisTemplate,
            @Value("${cachecraft.lock.lease-millis:5000}") long lockLeaseMillis) {
        this.redisTemplate = redisTemplate;
        this.lockLeaseMillis = lockLeaseMillis;
    }

    /**
     * Returns a cached value or executes one cache-fill operation under a Redis
     * lease. The follower never falls through to the database without owning
     * the lease.
     */
    public CacheResponse getOrLoad(
            long itemId,
            long delayMillis,
            Supplier<Optional<Item>> cacheLookup,
            Supplier<Item> loadAndCache) {
        Optional<Item> initialValue = cacheLookup.get();
        if (initialValue.isPresent()) {
            return new CacheResponse(initialValue.get(), true);
        }

        String lockKey = lockKey(itemId);
        String token = tryAcquire(lockKey);
        if (token != null) {
            return runAsLeader(lockKey, token, cacheLookup, loadAndCache);
        }

        long waitMillis = LockTimingPolicy.followerMaximumWaitMillis(delayMillis);
        long pollBaseMillis = LockTimingPolicy.pollBaseMillis(delayMillis);
        Optional<Item> valueFromLeader = awaitCacheValue(cacheLookup, waitMillis, pollBaseMillis, itemId);
        if (valueFromLeader.isPresent()) {
            return new CacheResponse(valueFromLeader.get(), true);
        }

        // One final election avoids an unnecessary failure if the old lease
        // expired just as this follower reached its wait deadline.
        token = tryAcquire(lockKey);
        if (token == null) {
            throw new LockUnavailableException(itemId);
        }
        return runAsLeader(lockKey, token, cacheLookup, loadAndCache);
    }

    private CacheResponse runAsLeader(
            String lockKey,
            String token,
            Supplier<Optional<Item>> cacheLookup,
            Supplier<Item> loadAndCache) {
        try {
            // A previous leader may have populated the cache before this
            // request acquired the lock. Do not repeat its database work.
            Optional<Item> itemAfterLock = cacheLookup.get();
            if (itemAfterLock.isPresent()) {
                return new CacheResponse(itemAfterLock.get(), true);
            }
            return new CacheResponse(loadAndCache.get(), false);
        } finally {
            releaseIfOwned(lockKey, token);
        }
    }

    private Optional<Item> awaitCacheValue(
            Supplier<Optional<Item>> cacheLookup,
            long maximumWaitMillis,
            long pollBaseMillis,
            long itemId) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maximumWaitMillis);
        while (System.nanoTime() < deadlineNanos) {
            Optional<Item> cachedItem = cacheLookup.get();
            if (cachedItem.isPresent()) {
                return cachedItem;
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }

            long pollMillis = LockTimingPolicy.randomizedPollMillis(pollBaseMillis);
            long sleepMillis = Math.min(pollMillis, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new LockUnavailableException(itemId, exception);
            }
        }
        return Optional.empty();
    }

    private String tryAcquire(String lockKey) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, token, Duration.ofMillis(lockLeaseMillis));
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    private void releaseIfOwned(String lockKey, String token) {
        redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), token);
    }

    private String lockKey(long itemId) {
        return LOCK_KEY_PREFIX + itemId;
    }
}
