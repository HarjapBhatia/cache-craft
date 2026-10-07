package com.cachecraft.controller;

import com.cachecraft.model.CacheResponse;
import com.cachecraft.model.Item;
import com.cachecraft.service.InvalidDistributedLockDelayException;
import com.cachecraft.service.LockUnavailableException;
import com.cachecraft.service.ItemService;
import com.cachecraft.service.UnsupportedStrategyException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ItemControllerTest {

    private final ItemService itemService = mock(ItemService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ItemController(itemService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void returnsItemJsonAndCacheMissHeader() throws Exception {
        Item item = new Item(42, "Item 42", 1654, "description");
        when(itemService.getItem(42, "cache-aside", 30, 0, 0.10))
                .thenReturn(new CacheResponse(item, false));

        mockMvc.perform(get("/api/items/42")
                        .param("strategy", "cache-aside")
                        .param("ttl", "30")
                        .param("delay", "0")
                        .param("jitter", "0.10"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "MISS"))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.price").value(1654));
    }

    @Test
    void returnsCacheHitHeaderForCachedResponse() throws Exception {
        Item item = new Item(42, "Item 42", 1654, "description");
        when(itemService.getItem(42, "cache-aside", 30, 0, 0.10))
                .thenReturn(new CacheResponse(item, true));

        mockMvc.perform(get("/api/items/42")
                        .param("strategy", "cache-aside")
                        .param("ttl", "30")
                        .param("delay", "0")
                        .param("jitter", "0.10"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "HIT"));
    }

    @Test
    void mapsUnsupportedStrategyToBadRequest() throws Exception {
        when(itemService.getItem(42, "distributed-lock", 30, 0, 0.10))
                .thenThrow(new UnsupportedStrategyException("distributed-lock"));

        mockMvc.perform(get("/api/items/42")
                        .param("strategy", "distributed-lock")
                        .param("ttl", "30")
                        .param("delay", "0")
                        .param("jitter", "0.10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_STRATEGY"));
    }

    @Test
    void mapsTooLongDistributedLockDelayToBadRequest() throws Exception {
        when(itemService.getItem(42, "distributed-lock", 30, 1_000, 0.10))
                .thenThrow(new InvalidDistributedLockDelayException(1_000));

        mockMvc.perform(get("/api/items/42")
                        .param("strategy", "distributed-lock")
                        .param("ttl", "30")
                        .param("delay", "1000")
                        .param("jitter", "0.10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DISTRIBUTED_LOCK_DELAY"));
    }

    @Test
    void mapsLockTimeoutToServiceUnavailable() throws Exception {
        when(itemService.getItem(42, "distributed-lock", 30, 0, 0.10))
                .thenThrow(new LockUnavailableException(42));

        mockMvc.perform(get("/api/items/42")
                        .param("strategy", "distributed-lock")
                        .param("ttl", "30")
                        .param("delay", "0")
                        .param("jitter", "0.10"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CACHE_LOCK_UNAVAILABLE"));
    }

    @Test
    void mapsDatabaseConnectionAcquisitionFailureToServiceUnavailable() throws Exception {
        when(itemService.getItem(42, "no-cache", 30, 0, 0.10))
                .thenThrow(new CannotGetJdbcConnectionException("pool unavailable"));

        mockMvc.perform(get("/api/items/42")
                        .param("strategy", "no-cache")
                        .param("ttl", "30")
                        .param("delay", "0")
                        .param("jitter", "0.10"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_CONNECTION_TIMEOUT"));
    }

    @Test
    void requestParameterConstraintsRejectInvalidValues() throws Exception {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Method method = ItemController.class.getMethod(
                    "getItem", long.class, String.class, long.class, long.class, double.class);
            var violations = factory.getValidator().forExecutables().validateParameters(
                    new ItemController(itemService), method,
                    new Object[]{0L, "", 0L, -1L, 1.1});

            assertEquals(5, violations.size());
            assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().contains("id")));
        }
    }
}
