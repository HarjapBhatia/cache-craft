package com.cachecraft.cache;

import com.cachecraft.model.Item;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

/** Owns Redis item keys, JSON encoding, cache reads, TTL writes, and test reset. */
@Component
public class ItemCache {

    private static final String ITEM_KEY_PREFIX = "item:";
    private static final String ITEM_KEY_PATTERN = ITEM_KEY_PREFIX + "*";
    private static final long MINIMUM_TTL_MILLIS = 1;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ItemCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** Returns an empty value on a normal Redis miss. */
    public Optional<Item> findById(long id) {
        String serializedItem = redisTemplate.opsForValue().get(itemKey(id));
        if (serializedItem == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readValue(serializedItem, Item.class));
        } catch (JsonProcessingException exception) {
            throw new CacheSerializationException("Could not deserialize cached item %d".formatted(id), exception);
        }
    }

    /** Writes an item with a symmetric, independently sampled TTL adjustment. */
    public void put(Item item, long baseTtlSeconds, double jitterFraction) {
        long ttlMillis = effectiveTtlMillis(baseTtlSeconds, jitterFraction);
        try {
            String serializedItem = objectMapper.writeValueAsString(item);
            redisTemplate.opsForValue().set(itemKey(item.id()), serializedItem, Duration.ofMillis(ttlMillis));
        } catch (JsonProcessingException exception) {
            throw new CacheSerializationException("Could not serialize item %d".formatted(item.id()), exception);
        }
    }

    /**
     * Pipelines writes with one absolute Redis expiration, preventing the
     * write loop itself from introducing accidental expiry jitter.
     */
    public void putAllWithAbsoluteExpiry(Collection<Item> items, long expiresAtEpochMillis) {
        if (items.isEmpty()) {
            return;
        }
        if (expiresAtEpochMillis <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("Absolute cache expiry must be in the future");
        }

        List<SerializedItem> serializedItems = items.stream()
                .map(this::serialize)
                .toList();
        Expiration expiration = Expiration.unixTimestamp(expiresAtEpochMillis, TimeUnit.MILLISECONDS);

        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (SerializedItem serializedItem : serializedItems) {
                connection.stringCommands().set(
                        serializedItem.key().getBytes(StandardCharsets.UTF_8),
                        serializedItem.value().getBytes(StandardCharsets.UTF_8),
                        expiration,
                        SetOption.UPSERT);
            }
            return null;
        });
    }

    /**
     * Removes item entries for isolated integration tests and explicit local
     * experiment resets. Call only when no workload is using the cache.
     */
    public void clearItems() {
        Set<String> itemKeys = redisTemplate.keys(ITEM_KEY_PATTERN);
        if (itemKeys != null && !itemKeys.isEmpty()) {
            redisTemplate.delete(itemKeys);
        }
    }

    private long effectiveTtlMillis(long baseTtlSeconds, double jitterFraction) {
        if (jitterFraction == 0.0) {
            return Math.max(MINIMUM_TTL_MILLIS, Math.round(baseTtlSeconds * 1000.0));
        }

        double unitAdjustment = ThreadLocalRandom.current().nextDouble(-jitterFraction, jitterFraction);
        double ttlMillis = baseTtlSeconds * 1000.0 * (1.0 + unitAdjustment);
        // Redis expiration is millisecond based. Keep the entry alive for at
        // least one millisecond when large jitter nearly cancels a short TTL.
        return Math.max(MINIMUM_TTL_MILLIS, Math.round(ttlMillis));
    }

    private String itemKey(long id) {
        return ITEM_KEY_PREFIX + id;
    }

    private SerializedItem serialize(Item item) {
        Objects.requireNonNull(item, "item must not be null");
        try {
            return new SerializedItem(itemKey(item.id()), objectMapper.writeValueAsString(item));
        } catch (JsonProcessingException exception) {
            throw new CacheSerializationException("Could not serialize item %d".formatted(item.id()), exception);
        }
    }

    private record SerializedItem(String key, String value) {
    }
}
