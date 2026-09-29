package com.wedding.identity;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class AuthRateLimiterTest {
    private final AtomicLong now=new AtomicLong(1_000_000);
    private final AuthRateLimiter limiter=new AuthRateLimiter(now::get);

    private static void assertLimited(Runnable attempt) {
        assertEquals(429,assertThrows(ResponseStatusException.class,attempt::run).getStatusCode().value());
    }

    @Test void networkWindowAllowsTwentyAttemptsPerMinuteThenResets() {
        for(int i=0;i<AuthRateLimiter.NETWORK_LIMIT;i++) limiter.check("198.51.100.1","user"+i+"@example.test");
        assertLimited(()->limiter.check("198.51.100.1","other@example.test"));
        limiter.check("198.51.100.2","other@example.test");
        now.addAndGet(AuthRateLimiter.NETWORK_WINDOW_MS);
        limiter.check("198.51.100.1","other@example.test");
    }

    @Test void ipv6ClientsShareTheirSlash64() {
        for(int i=0;i<AuthRateLimiter.NETWORK_LIMIT;i++) limiter.check("2001:db8:1:2::"+Integer.toHexString(i+1));
        assertLimited(()->limiter.check("2001:db8:1:2:ffff:ffff:ffff:ffff"));
        limiter.check("2001:db8:1:3::1");
        assertEquals(AuthRateLimiter.networkKey("2001:db8:1:2::1"),AuthRateLimiter.networkKey("2001:0db8:0001:0002:abcd::9"));
    }

    @Test void accountFailuresFromManyAddressesLockOnlyThatAccount() {
        for(int i=0;i<AuthRateLimiter.ACCOUNT_FAILURE_LIMIT;i++) {
            limiter.check("203.0.113."+i,"Victim@Example.test");
            limiter.failed(" victim@example.test ");
        }
        assertLimited(()->limiter.check("203.0.113.200","victim@example.test"));
        limiter.check("203.0.113.200","someone-else@example.test");
        now.addAndGet(AuthRateLimiter.ACCOUNT_WINDOW_MS);
        limiter.check("203.0.113.201","victim@example.test");
    }

    @Test void successfulLoginClearsEarlierFailures() {
        for(int i=0;i<AuthRateLimiter.ACCOUNT_FAILURE_LIMIT-1;i++) limiter.failed("member@example.test");
        limiter.succeeded("member@example.test");
        for(int i=0;i<AuthRateLimiter.ACCOUNT_FAILURE_LIMIT-1;i++) limiter.failed("member@example.test");
        limiter.check("198.51.100.9","member@example.test");
    }

    @Test void lockedAccountDoesNotConsumeTheCallersNetworkBudget() {
        for(int i=0;i<AuthRateLimiter.ACCOUNT_FAILURE_LIMIT;i++) limiter.failed("locked@example.test");
        for(int i=0;i<AuthRateLimiter.NETWORK_LIMIT*2;i++) assertLimited(()->limiter.check("198.51.100.7","locked@example.test"));
        limiter.check("198.51.100.7","free@example.test");
    }

    @Test void mailBudgetIsPerAddressAndRefillsAfterTheWindow() {
        for(int i=0;i<AuthRateLimiter.MAIL_LIMIT;i++) assertTrue(limiter.tryMail("Inbox@Example.test"));
        assertFalse(limiter.tryMail(" inbox@example.test"));
        assertTrue(limiter.tryMail("other@example.test"));
        now.addAndGet(AuthRateLimiter.MAIL_WINDOW_MS);
        assertTrue(limiter.tryMail("inbox@example.test"));
    }

    @Test void fillingTheTableEvictsOldEntriesInsteadOfLockingOutNewClients() {
        // The previous implementation refused every unseen address once 10,000 were tracked.
        for(int i=0;i<AuthRateLimiter.MAX_TRACKED+50;i++) limiter.check("10."+(i>>16&255)+"."+(i>>8&255)+"."+(i&255));
        limiter.check("192.0.2.1");
        limiter.check("192.0.2.2","new@example.test");
    }
}
