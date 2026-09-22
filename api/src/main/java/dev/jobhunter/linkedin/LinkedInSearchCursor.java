package dev.jobhunter.linkedin;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process rotation cursor for the LinkedIn keyword x location search matrix.
 *
 * In-memory by design: the SEARCH token bucket in {@link LinkedInRateLimiterImpl} is itself
 * in-memory, and aggregator_run rows are rewritten wholesale by
 * AggregatorIngestionServiceImpl.updateAggregatorRun, so a DB cursor column would be clobbered
 * on every run.
 */
@Component
public class LinkedInSearchCursor {

    private final AtomicInteger offset = new AtomicInteger(0);

    /** Current window start offset into the pair list. */
    public int getOffset() {
        return offset.get();
    }

    /**
     * Advance the cursor by {@code advanced} pairs, wrapping modulo {@code totalPairs}.
     * A non-positive {@code totalPairs} leaves the cursor unchanged.
     */
    public void advance(int advanced, int totalPairs) {
        if (totalPairs <= 0) {
            return;
        }
        offset.updateAndGet(current -> Math.floorMod(current + advanced, totalPairs));
    }
}
