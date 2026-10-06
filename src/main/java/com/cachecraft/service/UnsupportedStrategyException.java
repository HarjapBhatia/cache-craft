package com.cachecraft.service;

/** Signals a strategy value that is not available in the current build stage. */
public class UnsupportedStrategyException extends RuntimeException {

    public UnsupportedStrategyException(String strategy) {
        super("Unsupported cache strategy: " + strategy);
    }
}
