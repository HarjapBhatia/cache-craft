package com.cachecraft.cache;

import com.cachecraft.model.CacheResetResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExperimentCacheResetServiceTest {

    @Test
    void removesOnlyItemAndLockKeysAndReturnsTheirCounts() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.keys("item:*")).thenReturn(Set.of("item:1", "item:2"));
        when(redisTemplate.keys("lock:item:*")).thenReturn(Set.of("lock:item:1"));
        when(redisTemplate.delete(Set.of("item:1", "item:2"))).thenReturn(2L);
        when(redisTemplate.delete(Set.of("lock:item:1"))).thenReturn(1L);
        ExperimentCacheResetService resetService = new ExperimentCacheResetService(redisTemplate);

        assertEquals(new CacheResetResult(2, 1), resetService.reset());
        verify(redisTemplate).keys("item:*");
        verify(redisTemplate).keys("lock:item:*");
    }
}
