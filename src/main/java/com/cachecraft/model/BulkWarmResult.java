package com.cachecraft.model;

/** Result of one synchronized cache warm operation. */
public record BulkWarmResult(int warmedKeyCount, long expiresAtEpochMs) {
}
