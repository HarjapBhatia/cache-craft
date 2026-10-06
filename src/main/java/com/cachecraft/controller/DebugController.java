package com.cachecraft.controller;

import com.cachecraft.model.DbQueryCount;
import com.cachecraft.repository.ItemRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes per-process counters used as evidence in local experiments. */
@RestController
public class DebugController {

    private final ItemRepository itemRepository;

    public DebugController(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    @GetMapping("/debug/db-queries")
    public DbQueryCount getDbQueryCount() {
        return new DbQueryCount(itemRepository.getDbQueryCount());
    }
}
