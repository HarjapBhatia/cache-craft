package com.cachecraft.service;

import com.cachecraft.model.CacheResponse;
import com.cachecraft.model.Item;
import com.cachecraft.repository.ItemRepository;
import com.cachecraft.cache.ItemCache;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Coordinates the selected item retrieval strategy. */
@Service
public class ItemService {

    private final ItemRepository itemRepository;
    private final ItemCache itemCache;
    private final LocalLockService localLockService;
    private final RedisLockService redisLockService;

    public ItemService(
            ItemRepository itemRepository,
            ItemCache itemCache,
            LocalLockService localLockService,
            RedisLockService redisLockService) {
        this.itemRepository = itemRepository;
        this.itemCache = itemCache;
        this.localLockService = localLockService;
        this.redisLockService = redisLockService;
    }

    public CacheResponse getItem(
            long id,
            String strategy,
            long ttlSeconds,
            long delayMillis,
            double jitterFraction) {
        return switch (strategy) {
            case "no-cache" -> new CacheResponse(loadFromDatabase(id, delayMillis), false);
            case "cache-aside" -> loadCacheAside(id, ttlSeconds, delayMillis, 0.0);
            case "cache-aside-jitter" -> loadCacheAside(id, ttlSeconds, delayMillis, jitterFraction);
            case "local-single-flight" -> loadLocalSingleFlight(id, ttlSeconds, delayMillis);
            case "distributed-lock" -> loadWithDistributedLock(id, ttlSeconds, delayMillis);
            default -> throw new UnsupportedStrategyException(strategy);
        };
    }

    private CacheResponse loadWithDistributedLock(long id, long ttlSeconds, long delayMillis) {
        if (delayMillis >= 1_000) {
            throw new InvalidDistributedLockDelayException(delayMillis);
        }

        return redisLockService.getOrLoad(
                id,
                delayMillis,
                () -> itemCache.findById(id),
                () -> {
                    Item item = loadFromDatabase(id, delayMillis);
                    itemCache.put(item, ttlSeconds, 0.0);
                    return item;
                });
    }

    private CacheResponse loadCacheAside(long id, long ttlSeconds, long delayMillis, double jitterFraction) {
        Optional<Item> cachedItem = itemCache.findById(id);
        if (cachedItem.isPresent()) {
            return new CacheResponse(cachedItem.get(), true);
        }

        Item item = loadFromDatabase(id, delayMillis);
        itemCache.put(item, ttlSeconds, jitterFraction);
        return new CacheResponse(item, false);
    }

    private CacheResponse loadLocalSingleFlight(long id, long ttlSeconds, long delayMillis) {
        Optional<Item> cachedItem = itemCache.findById(id);
        if (cachedItem.isPresent()) {
            return new CacheResponse(cachedItem.get(), true);
        }

        // Recheck while holding the per-key lock because another request may
        // have filled Redis after this request's initial lookup.
        return localLockService.withLock(id, () -> {
            Optional<Item> itemAfterLock = itemCache.findById(id);
            if (itemAfterLock.isPresent()) {
                return new CacheResponse(itemAfterLock.get(), true);
            }

            Item item = loadFromDatabase(id, delayMillis);
            itemCache.put(item, ttlSeconds, 0.0);
            return new CacheResponse(item, false);
        });
    }

    private Item loadFromDatabase(long id, long delayMillis) {
        return itemRepository.findById(id, delayMillis)
                .orElseThrow(() -> new ItemNotFoundException(id));
    }
}
