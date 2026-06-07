package dev.jobhub.extraction;

import dev.jobhub.model.enums.ExtractionStatus;

import java.time.Duration;
import java.util.List;

public record ExtractionResult(
        List<RawJobData> jobs,
        int totalFound,
        ExtractionStatus status,
        String errorMessage,
        Duration elapsed
) {

    public static ExtractionResult success(List<RawJobData> jobs, Duration elapsed) {
        return new ExtractionResult(jobs, jobs.size(), ExtractionStatus.SUCCESS, null, elapsed);
    }

    public static ExtractionResult empty(Duration elapsed) {
        return new ExtractionResult(List.of(), 0, ExtractionStatus.EMPTY, null, elapsed);
    }

    public static ExtractionResult error(String message, Duration elapsed) {
        return new ExtractionResult(List.of(), 0, ExtractionStatus.ERROR, message, elapsed);
    }

    public static ExtractionResult rateLimited(Duration elapsed) {
        return new ExtractionResult(List.of(), 0, ExtractionStatus.RATE_LIMITED, "Rate limited (429) - will retry next cycle", elapsed);
    }

    public static ExtractionResult protectedEndpoint(Duration elapsed) {
        return new ExtractionResult(List.of(), 0, ExtractionStatus.PROTECTED, "Protected endpoint - requires authentication", elapsed);
    }
}
