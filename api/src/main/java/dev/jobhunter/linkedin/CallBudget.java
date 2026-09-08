package dev.jobhunter.linkedin;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small call-budget counter used to cap the number of external (LinkedIn MCP)
 * calls performed while resolving a single job URL.
 */
public class CallBudget {

    private final int maxCalls;
    private final AtomicInteger used = new AtomicInteger();

    public CallBudget(int maxCalls) {
        this.maxCalls = maxCalls;
    }

    /** Returns true if a call slot was available and consumed; false if budget exhausted. */
    public boolean trySpend() {
        return used.incrementAndGet() <= maxCalls;
    }

    public int used() {
        return used.get();
    }

    public boolean exhausted() {
        return used.get() >= maxCalls;
    }
}