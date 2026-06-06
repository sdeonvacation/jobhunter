package dev.jobhub.linkedin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LinkedInRateLimiterImplTest {

    private LinkedInRateLimiterImpl rateLimiter;

    @BeforeEach
    void setUp() {
        LinkedInMcpProperties properties = new LinkedInMcpProperties(
                true,
                "http://localhost:8000",
                "/mcp",
                30,
                new LinkedInMcpProperties.RateLimitConfig(20, 15, 10, 50),
                new LinkedInMcpProperties.CircuitBreakerConfig(5, 15),
                new LinkedInMcpProperties.EnrichmentConfig(false, 10, 3000)
        );
        rateLimiter = new LinkedInRateLimiterImpl(properties);
    }

    @Test
    @DisplayName("Should acquire token when bucket has capacity")
    void shouldAcquireWhenCapacityAvailable() {
        assertThat(rateLimiter.acquire(ToolCategory.SEARCH)).isTrue();
    }

    @Test
    @DisplayName("Should exhaust category bucket after max tokens consumed")
    void shouldExhaustCategoryBucket() {
        // Search has 20 per hour
        for (int i = 0; i < 20; i++) {
            assertThat(rateLimiter.acquire(ToolCategory.SEARCH)).isTrue();
        }
        assertThat(rateLimiter.acquire(ToolCategory.SEARCH)).isFalse();
    }

    @Test
    @DisplayName("Should exhaust global bucket across categories")
    void shouldExhaustGlobalBucket() {
        // Global is 50: consume 20 SEARCH + 15 PROFILE + 10 ACTION = 45, then 5 more SEARCH-like
        // But SEARCH is already exhausted after 20. Use fresh limiter with low global.
        LinkedInMcpProperties props = new LinkedInMcpProperties(
                true, "http://localhost:8000", "/mcp", 30,
                new LinkedInMcpProperties.RateLimitConfig(100, 100, 100, 5),
                new LinkedInMcpProperties.CircuitBreakerConfig(5, 15),
                new LinkedInMcpProperties.EnrichmentConfig(false, 10, 3000)
        );
        LinkedInRateLimiterImpl limiter = new LinkedInRateLimiterImpl(props);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.acquire(ToolCategory.SEARCH)).isTrue();
        }
        // Global exhausted even though category still has tokens
        assertThat(limiter.acquire(ToolCategory.SEARCH)).isFalse();
    }

    @Test
    @DisplayName("Should report remaining tokens correctly")
    void shouldReportRemainingTokens() {
        assertThat(rateLimiter.getRemainingTokens(ToolCategory.SEARCH)).isEqualTo(20);
        rateLimiter.acquire(ToolCategory.SEARCH);
        assertThat(rateLimiter.getRemainingTokens(ToolCategory.SEARCH)).isEqualTo(19);
    }

    @Test
    @DisplayName("Should respect different category limits")
    void shouldRespectDifferentCategoryLimits() {
        // ACTION has 10 per hour
        for (int i = 0; i < 10; i++) {
            assertThat(rateLimiter.acquire(ToolCategory.ACTION)).isTrue();
        }
        assertThat(rateLimiter.acquire(ToolCategory.ACTION)).isFalse();
        // PROFILE should still work (15 - 0 consumed)
        assertThat(rateLimiter.acquire(ToolCategory.PROFILE)).isTrue();
    }

    @Test
    @DisplayName("acquireOrWait should return false when maxWait exceeded")
    void acquireOrWaitShouldTimeOut() {
        // Exhaust SEARCH
        for (int i = 0; i < 20; i++) {
            rateLimiter.acquire(ToolCategory.SEARCH);
        }

        long start = System.currentTimeMillis();
        boolean result = rateLimiter.acquireOrWait(ToolCategory.SEARCH, Duration.ofMillis(250));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result).isFalse();
        assertThat(elapsed).isGreaterThanOrEqualTo(200); // waited at least ~200ms
    }

    @Test
    @DisplayName("getRemainingTokens returns min of category and global")
    void getRemainingTokensReturnsMinOfCategoryAndGlobal() {
        // With global=50 and search=20, remaining should be min(20, 50)=20
        assertThat(rateLimiter.getRemainingTokens(ToolCategory.SEARCH)).isEqualTo(20);

        // Consume from profile to lower global
        for (int i = 0; i < 15; i++) {
            rateLimiter.acquire(ToolCategory.PROFILE);
        }
        // Global now at 35, search category at 20 → remaining = min(20, 35) = 20
        assertThat(rateLimiter.getRemainingTokens(ToolCategory.SEARCH)).isEqualTo(20);
    }

    @Test
    @DisplayName("Should handle concurrent acquire without corruption")
    void shouldHandleConcurrentAcquire() throws InterruptedException {
        LinkedInMcpProperties props = new LinkedInMcpProperties(
                true, "http://localhost:8000", "/mcp", 30,
                new LinkedInMcpProperties.RateLimitConfig(100, 100, 100, 100),
                new LinkedInMcpProperties.CircuitBreakerConfig(5, 15),
                new LinkedInMcpProperties.EnrichmentConfig(false, 10, 3000)
        );
        LinkedInRateLimiterImpl limiter = new LinkedInRateLimiterImpl(props);

        Thread[] threads = new Thread[10];
        int[] successes = new int[1];
        Object lock = new Object();

        for (int i = 0; i < 10; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10; j++) {
                    if (limiter.acquire(ToolCategory.SEARCH)) {
                        synchronized (lock) {
                            successes[0]++;
                        }
                    }
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }

        // Total successes should be exactly 100 (all succeed with capacity=100 each)
        assertThat(successes[0]).isEqualTo(100);
    }
}
