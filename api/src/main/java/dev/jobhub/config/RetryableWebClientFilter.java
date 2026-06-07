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
 * <p>
 * 429 responses: up to 3 retries with exponential backoff (2s, 4s, 8s) or
 * Retry-After header if present. 5xx responses: 1 retry with 2s fixed delay.
 * Does NOT retry on 4xx (except 429) or network timeouts.
 * After retry exhaustion, the error response passes through normally so
 * downstream status handlers (e.g. .retrieve()) work as expected.
 */
@Slf4j
public class RetryableWebClientFilter implements ExchangeFilterFunction {

    static final int MAX_RETRIES_429 = 3;
    static final int MAX_RETRIES_5XX = 1;
    static final Duration BASE_BACKOFF_429 = Duration.ofSeconds(2);
    static final Duration DEFAULT_BACKOFF_5XX = Duration.ofSeconds(2);
    static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(60);

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        AtomicInteger retryCount429 = new AtomicInteger(0);
        AtomicInteger retryCount5xx = new AtomicInteger(0);

        return Mono.defer(() -> next.exchange(request)
                .flatMap(response -> {
                    int statusCode = response.statusCode().value();

                    if (statusCode == 429) {
                        int count = retryCount429.incrementAndGet();
                        if (count > MAX_RETRIES_429) {
                            return Mono.just(response);
                        }
                        Duration delay = computeDelay429(response.headers(), count);
                        log.warn("HTTP 429 from {} - retry {}/{} after {}ms",
                                request.url(), count, MAX_RETRIES_429, delay.toMillis());
                        return response.releaseBody()
                                .then(Mono.error(new RetryableRequestException(statusCode, delay)));
                    }

                    if (response.statusCode().is5xxServerError()) {
                        int count = retryCount5xx.incrementAndGet();
                        if (count > MAX_RETRIES_5XX) {
                            return Mono.just(response);
                        }
                        log.warn("HTTP {} from {} - retry {}/{} after {}ms",
                                statusCode, request.url(), count, MAX_RETRIES_5XX, DEFAULT_BACKOFF_5XX.toMillis());
                        return response.releaseBody()
                                .then(Mono.error(new RetryableRequestException(statusCode, DEFAULT_BACKOFF_5XX)));
                    }

                    return Mono.just(response);
                }))
        .retryWhen(Retry.max(MAX_RETRIES_429)
                .filter(ex -> ex instanceof RetryableRequestException)
                .doBeforeRetryAsync(signal -> {
                    RetryableRequestException ex = (RetryableRequestException) signal.failure();
                    return Mono.delay(ex.getDelay()).then();
                }));
    }

    /**
     * Compute delay for 429 retry: use Retry-After header if present,
     * otherwise exponential backoff (2s * 2^(attempt-1)).
     */
    Duration computeDelay429(ClientResponse.Headers headers, int attemptNumber) {
        Duration headerDelay = parseRetryAfter(headers);
        if (headerDelay != null) {
            return headerDelay;
        }
        // Exponential backoff: 2s, 4s, 8s
        long backoffMs = BASE_BACKOFF_429.toMillis() * (1L << (attemptNumber - 1));
        return capDelay(Duration.ofMillis(backoffMs));
    }

    /**
     * Parse Retry-After header. Returns null if header is absent or blank,
     * allowing caller to fall back to exponential backoff.
     */
    Duration parseRetryAfter(ClientResponse.Headers headers) {
        String retryAfter = headers.asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter == null || retryAfter.isBlank()) {
            return null;
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
            // Unparseable, fall back to exponential
        }

        return null;
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
