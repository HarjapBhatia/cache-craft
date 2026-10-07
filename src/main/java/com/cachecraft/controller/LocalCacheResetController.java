package com.cachecraft.controller;

import com.cachecraft.cache.ExperimentCacheResetService;
import com.cachecraft.model.CacheResetResult;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes destructive cache reset only in the local experiment profile. */
@Profile("local")
@RestController
public class LocalCacheResetController {

    private final ExperimentCacheResetService resetService;

    public LocalCacheResetController(ExperimentCacheResetService resetService) {
        this.resetService = resetService;
    }

    @PostMapping("/debug/cache/reset")
    public CacheResetResult reset() {
        return resetService.reset();
    }
}
