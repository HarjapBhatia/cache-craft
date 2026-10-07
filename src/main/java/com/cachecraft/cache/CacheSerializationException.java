package com.cachecraft.cache;

/** Indicates that a cached JSON value cannot be encoded or decoded as an item. */
public class CacheSerializationException extends RuntimeException {

    public CacheSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
