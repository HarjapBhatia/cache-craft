package com.cachecraft.controller;

import com.cachecraft.model.CacheResponse;
import com.cachecraft.model.Item;
import com.cachecraft.service.ItemService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Binds and validates item HTTP requests, then maps service results to HTTP. */
@Validated
@RestController
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @GetMapping("/api/items/{id}")
    public ResponseEntity<Item> getItem(
            @PathVariable @Min(1) long id,
            @RequestParam(defaultValue = "no-cache") @NotBlank String strategy,
            @RequestParam(defaultValue = "30") @Min(1) long ttl,
            @RequestParam(defaultValue = "0") @Min(0) long delay,
            @RequestParam(defaultValue = "0.10") @DecimalMin("0.0") @DecimalMax("1.0") double jitter) {
        // ttl and jitter are accepted now so the API contract remains stable as
        // cache strategies arrive in Stage 3. no-cache deliberately ignores them.
        CacheResponse result = itemService.getItem(id, strategy, delay);
        return ResponseEntity.ok()
                .header("X-Cache", result.cacheHit() ? "HIT" : "MISS")
                .body(result.item());
    }

}
