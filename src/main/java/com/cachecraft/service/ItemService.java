package com.cachecraft.service;

import com.cachecraft.model.CacheResponse;
import com.cachecraft.model.Item;
import com.cachecraft.repository.ItemRepository;
import org.springframework.stereotype.Service;

/** Coordinates the selected item retrieval strategy. */
@Service
public class ItemService {

    private final ItemRepository itemRepository;

    public ItemService(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    public CacheResponse getItem(long id, String strategy, long delayMillis) {
        return switch (strategy) {
            case "no-cache" -> new CacheResponse(loadFromDatabase(id, delayMillis), false);
            default -> throw new UnsupportedStrategyException(strategy);
        };
    }

    private Item loadFromDatabase(long id, long delayMillis) {
        return itemRepository.findById(id, delayMillis)
                .orElseThrow(() -> new ItemNotFoundException(id));
    }
}
