package com.cachecraft.model;

import java.util.Objects;

/** Carries an item and its cache outcome from the service layer to HTTP. */
public record CacheResponse(Item item, boolean cacheHit) {
    public CacheResponse {
        Objects.requireNonNull(item, "item must not be null");
    }
}
