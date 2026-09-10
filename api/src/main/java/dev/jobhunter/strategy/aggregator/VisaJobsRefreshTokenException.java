package dev.jobhunter.strategy.aggregator;

/**
 * Thrown when the VisaJobs Supabase refresh-token flow fails or the refresh
 * token is not configured. Signals that the endpoint requires authentication
 * and cannot be fetched without operator intervention.
 */
public class VisaJobsRefreshTokenException extends RuntimeException {

    public VisaJobsRefreshTokenException(String message) {
        super(message);
    }

    public VisaJobsRefreshTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}