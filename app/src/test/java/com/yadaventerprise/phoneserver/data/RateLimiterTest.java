package com.yadaventerprise.phoneserver.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RateLimiterTest {

    @Test
    public void allowsUpToTheLimitThenBlocks() {
        RateLimiter limiter = new RateLimiter(3, 60000);
        assertTrue(limiter.tryAcquire("ip"));
        assertTrue(limiter.tryAcquire("ip"));
        assertTrue(limiter.tryAcquire("ip"));
        assertFalse(limiter.tryAcquire("ip"));
        assertFalse(limiter.tryAcquire("ip"));
    }

    @Test
    public void keysAreIndependent() {
        RateLimiter limiter = new RateLimiter(1, 60000);
        assertTrue(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("b"));
        assertFalse(limiter.tryAcquire("a"));
    }

    @Test
    public void nullKeyIsTreatedAsUnknown() {
        RateLimiter limiter = new RateLimiter(1, 60000);
        assertTrue(limiter.tryAcquire(null));
        assertFalse(limiter.tryAcquire("unknown"));
    }

    @Test
    public void minimumLimitAndWindowAreClamped() {
        RateLimiter limiter = new RateLimiter(0, 10);
        assertEquals(1, limiter.getMaxRequests());
        assertEquals(1000L, limiter.getWindowMillis());
    }

    @Test
    public void windowResetsAfterExpiry() throws Exception {
        RateLimiter limiter = new RateLimiter(1, 1000);
        assertTrue(limiter.tryAcquire("ip"));
        assertFalse(limiter.tryAcquire("ip"));
        Thread.sleep(1100);
        assertTrue(limiter.tryAcquire("ip"));
    }
}
