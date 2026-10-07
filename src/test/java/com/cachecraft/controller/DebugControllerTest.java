package com.cachecraft.controller;

import com.cachecraft.repository.ItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DebugControllerTest {

    private final ItemRepository itemRepository = mock(ItemRepository.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new DebugController(itemRepository))
            .build();

    @Test
    void returnsTheCurrentDbQueryCountForThisProcess() throws Exception {
        when(itemRepository.getDbQueryCount()).thenReturn(37L);

        mockMvc.perform(get("/debug/db-queries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dbQueryCount").value(37));
    }

    @Test
    void returnsZeroWhenNoQueriesHaveBeenExecuted() throws Exception {
        when(itemRepository.getDbQueryCount()).thenReturn(0L);

        mockMvc.perform(get("/debug/db-queries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dbQueryCount").value(0));
    }
}
