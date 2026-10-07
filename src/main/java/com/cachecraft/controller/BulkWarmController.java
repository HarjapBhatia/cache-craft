package com.cachecraft.controller;

import com.cachecraft.cache.ItemCache;
import com.cachecraft.model.BulkWarmResult;
import com.cachecraft.model.Item;
import com.cachecraft.repository.ItemRepository;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Initializes the complete seed set with one shared absolute Redis expiry. */
@Validated
@RestController
public class BulkWarmController {

    private final ItemRepository itemRepository;
    private final ItemCache itemCache;

    public BulkWarmController(ItemRepository itemRepository, ItemCache itemCache) {
        this.itemRepository = itemRepository;
        this.itemCache = itemCache;
    }

    @PostMapping("/debug/cache/bulk-warm")
    public BulkWarmResult bulkWarm(
            @RequestParam(defaultValue = "${cachecraft.cache.default-ttl-seconds:30}") @Min(1) long ttl) {
        List<Item> items = itemRepository.findAll();
        long expiresAtEpochMs = Math.addExact(System.currentTimeMillis(), Math.multiplyExact(ttl, 1_000));
        itemCache.putAllWithAbsoluteExpiry(items, expiresAtEpochMs);
        return new BulkWarmResult(items.size(), expiresAtEpochMs);
    }
}
