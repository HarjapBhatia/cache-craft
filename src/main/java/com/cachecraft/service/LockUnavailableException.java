package com.cachecraft.service;

/** Indicates that a follower could not read the cache or acquire the lock in time. */
public class LockUnavailableException extends RuntimeException {

    public LockUnavailableException(long itemId) {
        super("Could not obtain a cached item or distributed lock for item %d in time".formatted(itemId));
    }

    public LockUnavailableException(long itemId, Throwable cause) {
        super("Interrupted while waiting for distributed lock for item %d".formatted(itemId), cause);
    }
}
