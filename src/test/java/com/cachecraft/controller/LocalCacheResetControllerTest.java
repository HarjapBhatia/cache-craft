package com.cachecraft.controller;

import com.cachecraft.cache.ExperimentCacheResetService;
import com.cachecraft.model.CacheResetResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LocalCacheResetControllerTest {

    @Test
    void reportsTheItemAndLockKeysRemovedForAnExperimentReset() throws Exception {
        ExperimentCacheResetService resetService = mock(ExperimentCacheResetService.class);
        when(resetService.reset()).thenReturn(new CacheResetResult(10_000, 3));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new LocalCacheResetController(resetService)).build();

        mockMvc.perform(post("/debug/cache/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedItemKeys").value(10_000))
                .andExpect(jsonPath("$.deletedLockKeys").value(3));
    }
}
