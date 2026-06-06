package dev.jobhub.linkedin;

import java.time.Duration;

public interface LinkedInRateLimiter {

    boolean acquire(ToolCategory category);

    boolean acquireOrWait(ToolCategory category, Duration maxWait);

    int getRemainingTokens(ToolCategory category);
}
