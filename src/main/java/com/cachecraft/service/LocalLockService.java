package com.cachecraft.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Provides same-key request coordination within this single JVM only. */
@Service
public class LocalLockService {

    private final ConcurrentHashMap<String, LockEntry> locksByItemKey = new ConcurrentHashMap<>();

    /**
     * Runs one operation at a time for an item key and always releases its lock.
     * Entries are reclaimed when the final holder or waiter exits, so arbitrary
     * missing IDs cannot grow the map for the lifetime of the application.
     */
    public <T> T withLock(long itemId, Supplier<T> operation) {
        String key = itemKey(itemId);
        LockEntry entry = locksByItemKey.compute(key, (ignored, current) -> {
            LockEntry selected = current == null ? new LockEntry() : current;
            selected.activeUsers++;
            return selected;
        });

        entry.lock.lock();
        try {
            return operation.get();
        } finally {
            entry.lock.unlock();
            locksByItemKey.compute(key, (ignored, current) -> {
                if (current != entry) {
                    return current;
                }
                current.activeUsers--;
                return current.activeUsers == 0 ? null : current;
            });
        }
    }

    private String itemKey(long itemId) {
        return "item:" + itemId;
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        // Accessed only inside ConcurrentHashMap.compute for this key.
        private int activeUsers;
    }
}
