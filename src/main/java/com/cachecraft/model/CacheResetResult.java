package com.cachecraft.model;

/** Counts cache and lock entries removed before a local experiment. */
public record CacheResetResult(long deletedItemKeys, long deletedLockKeys) {
}
