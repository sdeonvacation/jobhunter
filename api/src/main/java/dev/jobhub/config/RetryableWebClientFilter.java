package dev.jobhub.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebClient filter that retries on 429 (rate limit) and 5xx (server errors).
 * Respects Retry-After header on 429 responses. Does NOT retry on 4xx (except 429)
 * or network timeouts. After retry exhaustion, the error response passes through
 * normally so downstream status handlers (e.g. .retrieve()) work as expected.
 */
@Slf4j
public class RetryableWebClientFilter implements ExchangeFilterFunction {

    private static final int MAX_RETRIES = 1;
    private static final Duration DEFAULT_BACKOFF_5XX = Duration.ofSeconds(2);
    private static final Duration DEFAULT_BACKOFF_429 = Duration.ofSeconds(5);
    private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(60);

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        AtomicInteger attempts = new AtomicInteger(0);

        return Mono.defer(() -> {
            int attempt = attempts.incrementAndGet();
            boolean lastAttempt = attempt > MAX_RETRIES;

            return next.exchange(request)
                    .flatMap(response -> {
                        int statusCode = response.statusCode().value();

                        if (lastAttempt) {
                            // Final attempt: pass response through regardless of status
                            return Mono.just(response);
                        }

                        if (statusCode == 429) {
                            Duration delay = parseRetryAfter(response.headers());
                            log.warn("HTTP 429 from {} - retrying after {}ms",
                                    request.url(), delay.toMillis());
                            return response.releaseBody()
                                    .then(Mono.error(new RetryableRequestException(statusCode, delay)));
                        }

                        if (response.statusCode().is5xxServerError()) {
                            log.warn("HTTP {} from {} - retrying after {}ms",
                                    statusCode, request.url(), DEFAULT_BACKOFF_5XX.toMillis());
                            return response.releaseBody()
                                    .then(Mono.error(new RetryableRequestException(statusCode, DEFAULT_BACKOFF_5XX)));
                        }

                        return Mono.just(response);
                    });
        })
        .retryWhen(Retry.max(MAX_RETRIES)
                .filter(ex -> ex instanceof RetryableRequestException)
                .doBeforeRetryAsync(signal -> {
                    RetryableRequestException ex = (RetryableRequestException) signal.failure();
                    return Mono.delay(ex.getDelay()).then();
                }));
    }

    Duration parseRetryAfter(ClientResponse.Headers headers) {
        String retryAfter = headers.asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter == null || retryAfter.isBlank()) {
            return DEFAULT_BACKOFF_429;
        }

        // Try parsing as seconds (integer)
        try {
            long seconds = Long.parseLong(retryAfter.trim());
            Duration delay = Duration.ofSeconds(seconds);
            return capDelay(delay);
        } catch (NumberFormatException ignored) {
            // Not a number, try as HTTP-date
        }

        // Try parsing as HTTP-date (RFC 7231)
        try {
            Instant retryAt = DateTimeFormatter.RFC_1123_DATE_TIME
                    .parse(retryAfter.trim(), Instant::from);
            Duration delay = Duration.between(Instant.now(), retryAt);
            if (delay.isNegative() || delay.isZero()) {
                return Duration.ofMillis(100); // Already past, retry immediately
            }
            return capDelay(delay);
        } catch (DateTimeParseException ignored) {
            // Unparseable, use default
        }

        return DEFAULT_BACKOFF_429;
    }

    private Duration capDelay(Duration delay) {
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    /**
     * Internal exception signaling a retryable HTTP response with a specific delay.
     */
    static class RetryableRequestException extends RuntimeException {
        private final int statusCode;
        private final Duration delay;

        RetryableRequestException(int statusCode, Duration delay) {
            super("HTTP " + statusCode + " (retryable)");
            this.statusCode = statusCode;
            this.delay = delay;
        }

        int getStatusCode() {
            return statusCode;
        }

        Duration getDelay() {
            return delay;
        }
    }
}
