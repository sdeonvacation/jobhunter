package dev.jobhunter.strategy;

import dev.jobhunter.model.enums.ExtractionStatus;

import java.time.Duration;
import java.util.List;

public record FetchResult(
    List<RawAggregatorJob> jobs,
    int totalFound,
    ExtractionStatus status,
    String errorMessage,
    Duration elapsed
) {

    public static FetchResult success(List<RawAggregatorJob> jobs, Duration elapsed) {
        return new FetchResult(jobs, jobs.size(), ExtractionStatus.SUCCESS, null, elapsed);
    }

    public static FetchResult empty(Duration elapsed) {
        return new FetchResult(List.of(), 0, ExtractionStatus.EMPTY, null, elapsed);
    }

    public static FetchResult error(String message, Duration elapsed) {
        return new FetchResult(List.of(), 0, ExtractionStatus.ERROR, message, elapsed);
    }

    public static FetchResult rateLimited(Duration elapsed) {
        return new FetchResult(List.of(), 0, ExtractionStatus.RATE_LIMITED, "Rate limited - will retry next cycle", elapsed);
    }

    public static FetchResult protectedEndpoint(Duration elapsed) {
        return new FetchResult(List.of(), 0, ExtractionStatus.PROTECTED, "Protected endpoint - requires authentication", elapsed);
    }
}
