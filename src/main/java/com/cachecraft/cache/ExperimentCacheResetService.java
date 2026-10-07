package com.cachecraft.cache;

import com.cachecraft.model.CacheResetResult;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

/** Clears only experiment item and lock keys when no workload is running. */
@Service
public class ExperimentCacheResetService {

    private static final String ITEM_KEY_PATTERN = "item:*";
    private static final String LOCK_KEY_PATTERN = "lock:item:*";

    private final StringRedisTemplate redisTemplate;

    public ExperimentCacheResetService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public CacheResetResult reset() {
        return new CacheResetResult(deleteMatching(ITEM_KEY_PATTERN), deleteMatching(LOCK_KEY_PATTERN));
    }

    private long deleteMatching(String keyPattern) {
        Set<String> keys = redisTemplate.keys(keyPattern);
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        Long deleted = redisTemplate.delete(keys);
        return deleted == null ? 0 : deleted;
    }
}
