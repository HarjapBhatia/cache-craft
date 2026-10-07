package com.cachecraft.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockTimingPolicyTest {

    @Test
    void derivesPollBaseAndFollowerWaitFromDelay() {
        assertEquals(5, LockTimingPolicy.pollBaseMillis(0));
        assertEquals(50, LockTimingPolicy.pollBaseMillis(250));
        assertEquals(50, LockTimingPolicy.pollBaseMillis(999));
        assertEquals(200, LockTimingPolicy.followerMaximumWaitMillis(0));
        assertEquals(500, LockTimingPolicy.followerMaximumWaitMillis(250));
        assertEquals(1_998, LockTimingPolicy.followerMaximumWaitMillis(999));
    }

    @Test
    void randomizedPollsStayWithinGlobalBounds() {
        for (int attempt = 0; attempt < 500; attempt++) {
            long poll = LockTimingPolicy.randomizedPollMillis(50);
            assertTrue(poll >= LockTimingPolicy.MINIMUM_POLL_MILLIS);
            assertTrue(poll <= LockTimingPolicy.MAXIMUM_POLL_MILLIS);
        }
    }
}
