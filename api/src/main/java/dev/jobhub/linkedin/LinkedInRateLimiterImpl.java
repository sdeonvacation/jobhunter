package dev.jobhub.linkedin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class LinkedInRateLimiterImpl implements LinkedInRateLimiter {

    private static final long NANOS_PER_HOUR = Duration.ofHours(1).toNanos();
    private static final long SPIN_WAIT_MS = 100;

    private final Map<ToolCategory, TokenBucket> categoryBuckets = new ConcurrentHashMap<>();
    private final TokenBucket globalBucket;

    public LinkedInRateLimiterImpl(LinkedInMcpProperties properties) {
        LinkedInMcpProperties.RateLimitConfig config = properties.rateLimit();

        categoryBuckets.put(ToolCategory.SEARCH, new TokenBucket(config.searchPerHour()));
        categoryBuckets.put(ToolCategory.PROFILE, new TokenBucket(config.profilePerHour()));
        categoryBuckets.put(ToolCategory.ACTION, new TokenBucket(config.actionPerHour()));
        globalBucket = new TokenBucket(config.totalPerHour());
    }

    @Override
    public boolean acquire(ToolCategory category) {
        TokenBucket categoryBucket = categoryBuckets.get(category);
        if (categoryBucket == null) {
            return false;
        }

        // Refill both buckets before attempting acquire
        categoryBucket.refill();
        globalBucket.refill();

        // Consume from both category and global bucket atomically
        if (!categoryBucket.tryConsume()) {
            log.debug("Rate limit: category {} exhausted", category);
            return false;
        }
        if (!globalBucket.tryConsume()) {
            // Rollback category consumption
            categoryBucket.restore();
            log.debug("Rate limit: global bucket exhausted");
            return false;
        }

        log.debug("Rate limit acquired for {}: category={}, global={}",
                category, categoryBucket.getRemaining(), globalBucket.getRemaining());
        return true;
    }

    @Override
    public boolean acquireOrWait(ToolCategory category, Duration maxWait) {
        long deadline = System.nanoTime() + maxWait.toNanos();

        while (System.nanoTime() < deadline) {
            if (acquire(category)) {
                return true;
            }
            try {
                Thread.sleep(SPIN_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Override
    public int getRemainingTokens(ToolCategory category) {
        TokenBucket categoryBucket = categoryBuckets.get(category);
        if (categoryBucket == null) {
            return 0;
        }
        categoryBucket.refill();
        globalBucket.refill();
        return Math.min(categoryBucket.getRemaining(), globalBucket.getRemaining());
    }

    /**
     * AtomicLong-based token bucket with time-proportional refill.
     */
    static class TokenBucket {
        private final int maxTokens;
        private final AtomicLong tokens;
        private final AtomicLong lastRefillNanos;
        private final long refillIntervalNanos; // nanos per token

        TokenBucket(int maxTokens) {
            this.maxTokens = maxTokens;
            this.tokens = new AtomicLong(maxTokens);
            this.lastRefillNanos = new AtomicLong(System.nanoTime());
            this.refillIntervalNanos = maxTokens > 0 ? NANOS_PER_HOUR / maxTokens : NANOS_PER_HOUR;
        }

        void refill() {
            long now = System.nanoTime();
            long lastRefill = lastRefillNanos.get();
            long elapsed = now - lastRefill;

            if (elapsed <= 0) return;

            long newTokens = elapsed / refillIntervalNanos;
            if (newTokens <= 0) return;

            if (lastRefillNanos.compareAndSet(lastRefill, lastRefill + (newTokens * refillIntervalNanos))) {
                long current = tokens.get();
                long updated = Math.min(current + newTokens, maxTokens);
                tokens.compareAndSet(current, updated);
            }
        }

        boolean tryConsume() {
            while (true) {
                long current = tokens.get();
                if (current <= 0) return false;
                if (tokens.compareAndSet(current, current - 1)) return true;
            }
        }

        void restore() {
            tokens.incrementAndGet();
        }

        int getRemaining() {
            return (int) Math.max(0, tokens.get());
        }
    }
}
