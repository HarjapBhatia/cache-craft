package com.cachecraft.controller;

import com.cachecraft.cache.ItemCache;
import com.cachecraft.model.Item;
import com.cachecraft.repository.ItemRepository;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BulkWarmControllerTest {

    @Test
    void warmsTheSeedSetWithAReportedSharedAbsoluteExpiry() throws Exception {
        ItemRepository itemRepository = mock(ItemRepository.class);
        ItemCache itemCache = mock(ItemCache.class);
        List<Item> items = List.of(
                new Item(1, "Item 1", 137, "one"),
                new Item(2, "Item 2", 174, "two"));
        when(itemRepository.findAll()).thenReturn(items);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new BulkWarmController(itemRepository, itemCache)).build();
        long before = System.currentTimeMillis();

        mockMvc.perform(post("/debug/cache/bulk-warm").param("ttl", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warmedKeyCount").value(2))
                .andExpect(jsonPath("$.expiresAtEpochMs").isNumber());

        org.mockito.ArgumentCaptor<Long> expiry = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(itemCache).putAllWithAbsoluteExpiry(eq(items), expiry.capture());
        assertTrue(expiry.getValue() >= before + 30_000);
        assertTrue(expiry.getValue() <= System.currentTimeMillis() + 30_000);
    }

    @Test
    void declaresNonPositiveTtlInvalidForSpringMethodValidation() throws Exception {
        ItemRepository itemRepository = mock(ItemRepository.class);
        ItemCache itemCache = mock(ItemCache.class);
        BulkWarmController controller = new BulkWarmController(itemRepository, itemCache);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var method = BulkWarmController.class.getMethod("bulkWarm", long.class);
            var violations = factory.getValidator().forExecutables().validateParameters(
                    controller, method, new Object[]{0L});

            assertTrue(violations.stream().anyMatch(violation ->
                    violation.getPropertyPath().toString().contains("ttl")));
        }
    }
}
