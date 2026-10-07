package com.cachecraft.service;

/** Signals a database delay outside the supported distributed-lock profile. */
public class InvalidDistributedLockDelayException extends RuntimeException {

    public InvalidDistributedLockDelayException(long delayMillis) {
        super("Distributed-lock strategy supports delay values below 1000 ms; received %d ms"
                .formatted(delayMillis));
    }
}
