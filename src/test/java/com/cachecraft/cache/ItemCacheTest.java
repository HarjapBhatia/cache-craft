package com.cachecraft.cache;

import com.cachecraft.model.Item;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.types.Expiration;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemCacheTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ItemCache itemCache = new ItemCache(redisTemplate, objectMapper);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    @BeforeEach
    void exposeValueOperations() {
        when(redisTemplate.opsForValue()).thenReturn(values);
    }

    @Test
    void cacheMissReturnsEmpty() {
        when(values.get("item:42")).thenReturn(null);

        assertTrue(itemCache.findById(42).isEmpty());
    }

    @Test
    void cachedJsonDeserializesToItem() throws Exception {
        Item expected = new Item(42, "Item 42", 1654, "description");
        when(values.get("item:42")).thenReturn(objectMapper.writeValueAsString(expected));

        assertEquals(expected, itemCache.findById(42).orElseThrow());
    }

    @Test
    void corruptJsonFailsVisiblyInsteadOfBecomingAMiss() {
        when(values.get("item:42")).thenReturn("not-json");

        assertThrows(CacheSerializationException.class, () -> itemCache.findById(42));
    }

    @Test
    void nonJitteredWriteUsesExactBaseTtlAndExpectedKey() {
        Item item = new Item(42, "Item 42", 1654, "description");

        itemCache.put(item, 30, 0.0);

        verify(values).set(eq("item:42"), eq("{\"id\":42,\"name\":\"Item 42\",\"price\":1654,\"description\":\"description\"}"), eq(Duration.ofSeconds(30)));
    }

    @Test
    void jitteredWriteStaysInsideConfiguredSymmetricBounds() {
        Item item = new Item(7, "Item 7", 359, "description");

        itemCache.put(item, 30, 0.10);

        org.mockito.ArgumentCaptor<Duration> ttl = org.mockito.ArgumentCaptor.forClass(Duration.class);
        verify(values).set(eq("item:7"), org.mockito.ArgumentMatchers.anyString(), ttl.capture());
        assertTrue(ttl.getValue().toMillis() >= 27_000);
        assertTrue(ttl.getValue().toMillis() <= 33_000);
    }

    @Test
    void clearItemsDeletesOnlyMatchingItemKeysWhenPresent() {
        when(redisTemplate.keys("item:*")).thenReturn(Set.of("item:1", "item:2"));

        itemCache.clearItems();

        verify(redisTemplate).delete(anyCollection());
    }

    @Test
    void clearItemsDoesNothingWhenRedisHasNoItemKeys() {
        when(redisTemplate.keys("item:*")).thenReturn(Set.of());

        itemCache.clearItems();

        verify(redisTemplate, never()).delete(anyCollection());
    }

    @Test
    @SuppressWarnings("unchecked")
    void bulkWarmPipelinesEveryItemWithOneAbsoluteExpiry() {
        RedisConnection connection = mock(RedisConnection.class);
        RedisStringCommands stringCommands = mock(RedisStringCommands.class);
        when(connection.stringCommands()).thenReturn(stringCommands);
        when(redisTemplate.executePipelined(org.mockito.ArgumentMatchers.<RedisCallback<Object>>any()))
                .thenAnswer(invocation -> {
                    RedisCallback<Object> callback = invocation.getArgument(0);
                    callback.doInRedis(connection);
                    return List.of();
                });
        long expiresAtEpochMs = System.currentTimeMillis() + 30_000;

        itemCache.putAllWithAbsoluteExpiry(
                List.of(new Item(1, "Item 1", 137, "one"), new Item(2, "Item 2", 174, "two")),
                expiresAtEpochMs);

        org.mockito.ArgumentCaptor<byte[]> keys = org.mockito.ArgumentCaptor.forClass(byte[].class);
        org.mockito.ArgumentCaptor<byte[]> values = org.mockito.ArgumentCaptor.forClass(byte[].class);
        org.mockito.ArgumentCaptor<Expiration> expirations = org.mockito.ArgumentCaptor.forClass(Expiration.class);
        verify(stringCommands, times(2)).set(keys.capture(), values.capture(), expirations.capture(), eq(SetOption.UPSERT));
        assertEquals(List.of("item:1", "item:2"), keys.getAllValues().stream()
                .map(value -> new String(value, StandardCharsets.UTF_8)).toList());
        assertEquals(expiresAtEpochMs, expirations.getAllValues().getFirst().getExpirationTimeInMilliseconds());
        assertEquals(expiresAtEpochMs, expirations.getAllValues().getLast().getExpirationTimeInMilliseconds());
        assertTrue(expirations.getAllValues().stream().allMatch(Expiration::isUnixTimestamp));
    }
}
