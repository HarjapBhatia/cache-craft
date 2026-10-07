package com.cachecraft.service;

import java.util.concurrent.ThreadLocalRandom;

/** Pure calculations for distributed-lock follower polling. */
public final class LockTimingPolicy {

    public static final long MINIMUM_POLL_MILLIS = 5;
    public static final long MAXIMUM_POLL_MILLIS = 50;
    public static final long MINIMUM_FOLLOWER_WAIT_MILLIS = 200;

    private LockTimingPolicy() {
    }

    public static long pollBaseMillis(long delayMillis) {
        return clamp(delayMillis / 5, MINIMUM_POLL_MILLIS, MAXIMUM_POLL_MILLIS);
    }

    public static long followerMaximumWaitMillis(long delayMillis) {
        return Math.max(MINIMUM_FOLLOWER_WAIT_MILLIS, 2 * delayMillis);
    }

    /** Adds bounded 20 percent random variation without escaping 5 to 50 ms. */
    public static long randomizedPollMillis(long baseMillis) {
        long variation = Math.max(1, baseMillis / 5);
        long lowerBound = clamp(baseMillis - variation, MINIMUM_POLL_MILLIS, MAXIMUM_POLL_MILLIS);
        long upperBound = clamp(baseMillis + variation, MINIMUM_POLL_MILLIS, MAXIMUM_POLL_MILLIS);
        return ThreadLocalRandom.current().nextLong(lowerBound, upperBound + 1);
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
