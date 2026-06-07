package dev.jobhunter.config;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WireMockTest
class RetryableWebClientFilterTest {

    private WebClient webClient;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        webClient = WebClient.builder()
                .baseUrl(wmInfo.getHttpBaseUrl())
                .filter(new RetryableWebClientFilter())
                .build();
    }

    @Test
    void successfulRequest_noRetry() {
        stubFor(get("/api/data").willReturn(ok("hello")));

        String result = webClient.get().uri("/api/data")
                .retrieve().bodyToMono(String.class).block();

        assertThat(result).isEqualTo("hello");
        verify(1, getRequestedFor(urlEqualTo("/api/data")));
    }

    @Test
    void serverError500_retriesOnce_thenPropagates() {
        stubFor(get("/api/data").willReturn(serverError().withBody("error")));

        assertThatThrownBy(() ->
                webClient.get().uri("/api/data")
                        .retrieve().bodyToMono(String.class).block()
        ).isInstanceOf(WebClientResponseException.class)
                .satisfies(ex -> {
                    WebClientResponseException wcex = (WebClientResponseException) ex;
                    assertThat(wcex.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                });

        // 1 original + 1 retry = 2 requests total
        verify(2, getRequestedFor(urlEqualTo("/api/data")));
    }

    @Test
    void serverError503_retriesOnce_succeedsOnRetry() {
        stubFor(get("/api/data")
                .inScenario("retry-success")
                .whenScenarioStateIs(STARTED)
                .willReturn(serviceUnavailable())
                .willSetStateTo("recovered"));

        stubFor(get("/api/data")
                .inScenario("retry-success")
                .whenScenarioStateIs("recovered")
                .willReturn(ok("recovered")));

        String result = webClient.get().uri("/api/data")
                .retrieve().bodyToMono(String.class).block();

        assertThat(result).isEqualTo("recovered");
        verify(2, getRequestedFor(urlEqualTo("/api/data")));
    }

    @Test
    void rateLimited429_retriesThreeTimes_thenPropagates() {
        stubFor(get("/api/data")
                .willReturn(aResponse().withStatus(429).withBody("rate limited")));

        assertThatThrownBy(() ->
                webClient.get().uri("/api/data")
                        .retrieve().bodyToMono(String.class).block()
        ).isInstanceOf(WebClientResponseException.class)
                .satisfies(ex -> {
                    WebClientResponseException wcex = (WebClientResponseException) ex;
                    assertThat(wcex.getStatusCode().value()).isEqualTo(429);
                });

        // 1 original + 3 retries = 4 requests
        verify(4, getRequestedFor(urlEqualTo("/api/data")));
    }

    @Test
    void rateLimited429_withRetryAfterHeader_succeedsOnRetry() {
        stubFor(get("/api/data")
                .inScenario("429-recovery")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429)
                        .withHeader(HttpHeaders.RETRY_AFTER, "1"))
                .willSetStateTo("available"));

        stubFor(get("/api/data")
                .inScenario("429-recovery")
                .whenScenarioStateIs("available")
                .willReturn(ok("success")));

        String result = webClient.get().uri("/api/data")
                .retrieve().bodyToMono(String.class).block();

        assertThat(result).isEqualTo("success");
        verify(2, getRequestedFor(urlEqualTo("/api/data")));
    }

    @Test
    void clientError404_noRetry() {
        stubFor(get("/api/data").willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() ->
                webClient.get().uri("/api/data")
                        .retrieve().bodyToMono(String.class).block()
        ).isInstanceOf(WebClientResponseException.class)
                .satisfies(ex -> {
                    WebClientResponseException wcex = (WebClientResponseException) ex;
                    assertThat(wcex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                });

        // Only 1 request - no retry on 4xx
        verify(1, getRequestedFor(urlEqualTo("/api/data")));
    }

    @Test
    void clientError403_noRetry() {
        stubFor(get("/api/data").willReturn(aResponse().withStatus(403)));

        assertThatThrownBy(() ->
                webClient.get().uri("/api/data")
                        .retrieve().bodyToMono(String.class).block()
        ).isInstanceOf(WebClientResponseException.class);

        verify(1, getRequestedFor(urlEqualTo("/api/data")));
    }

    @Test
    void clientError400_noRetry() {
        stubFor(get("/api/data").willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() ->
                webClient.get().uri("/api/data")
                        .retrieve().bodyToMono(String.class).block()
        ).isInstanceOf(WebClientResponseException.class);

        verify(1, getRequestedFor(urlEqualTo("/api/data")));
    }

    // --- Retry-After header parsing tests ---

    @Test
    void parseRetryAfter_seconds() {
        RetryableWebClientFilter filter = new RetryableWebClientFilter();
        var headers = mockHeadersWithRetryAfter("3");
        Duration delay = filter.parseRetryAfter(headers);
        assertThat(delay).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void parseRetryAfter_null_returnsNull() {
        RetryableWebClientFilter filter = new RetryableWebClientFilter();
        var headers = mockHeadersWithRetryAfter(null);
        Duration delay = filter.parseRetryAfter(headers);
        assertThat(delay).isNull();
    }

    @Test
    void parseRetryAfter_blank_returnsNull() {
        RetryableWebClientFilter filter = new RetryableWebClientFilter();
        var headers = mockHeadersWithRetryAfter("  ");
        Duration delay = filter.parseRetryAfter(headers);
        assertThat(delay).isNull();
    }

    @Test
    void parseRetryAfter_httpDate() {
        RetryableWebClientFilter filter = new RetryableWebClientFilter();
        ZonedDateTime future = ZonedDateTime.now().plusSeconds(10);
        String dateStr = DateTimeFormatter.RFC_1123_DATE_TIME.format(future);
        var headers = mockHeadersWithRetryAfter(dateStr);
        Duration delay = filter.parseRetryAfter(headers);
        assertThat(delay.toMillis()).isBetween(8000L, 11000L);
    }

    @Test
    void parseRetryAfter_excessiveValue_cappedAt60s() {
        RetryableWebClientFilter filter = new RetryableWebClientFilter();
        var headers = mockHeadersWithRetryAfter("120");
        Duration delay = filter.parseRetryAfter(headers);
        assertThat(delay).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void parseRetryAfter_invalidValue_returnsNull() {
        RetryableWebClientFilter filter = new RetryableWebClientFilter();
        var headers = mockHeadersWithRetryAfter("not-a-number-or-date");
        Duration delay = filter.parseRetryAfter(headers);
        assertThat(delay).isNull();
    }

    @Test
    void parseRetryAfter_zero_returnsZeroSeconds() {
        RetryableWebClientFilter filter = new RetryableWebClientFilter();
        var headers = mockHeadersWithRetryAfter("0");
        Duration delay = filter.parseRetryAfter(headers);
        assertThat(delay).isEqualTo(Duration.ofSeconds(0));
    }

    // --- Exponential backoff tests ---

    @Test
    void computeDelay429_noHeader_exponentialBackoff() {
        RetryableWebClientFilter filter = new RetryableWebClientFilter();
        var headers = mockHeadersWithRetryAfter(null);

        assertThat(filter.computeDelay429(headers, 1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(filter.computeDelay429(headers, 2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(filter.computeDelay429(headers, 3)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void computeDelay429_withHeader_usesHeaderValue() {
        RetryableWebClientFilter filter = new RetryableWebClientFilter();
        var headers = mockHeadersWithRetryAfter("10");

        // Header value takes precedence regardless of attempt number
        assertThat(filter.computeDelay429(headers, 1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(filter.computeDelay429(headers, 3)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void computeDelay429_exponentialBackoff_cappedAt60s() {
        RetryableWebClientFilter filter = new RetryableWebClientFilter();
        var headers = mockHeadersWithRetryAfter(null);

        // Attempt 6 would be 2 * 2^5 = 64s, capped at 60s
        assertThat(filter.computeDelay429(headers, 6)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void rateLimited429_exponentialBackoff_succeedsOnThirdRetry() {
        // First 3 attempts return 429, 4th succeeds
        stubFor(get("/api/data")
                .inScenario("429-multi-retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("retry-1"));

        stubFor(get("/api/data")
                .inScenario("429-multi-retry")
                .whenScenarioStateIs("retry-1")
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("retry-2"));

        stubFor(get("/api/data")
                .inScenario("429-multi-retry")
                .whenScenarioStateIs("retry-2")
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("retry-3"));

        stubFor(get("/api/data")
                .inScenario("429-multi-retry")
                .whenScenarioStateIs("retry-3")
                .willReturn(ok("finally")));

        String result = webClient.get().uri("/api/data")
                .retrieve().bodyToMono(String.class).block();

        assertThat(result).isEqualTo("finally");
        verify(4, getRequestedFor(urlEqualTo("/api/data")));
    }

    private org.springframework.web.reactive.function.client.ClientResponse.Headers mockHeadersWithRetryAfter(
            String retryAfterValue) {
        HttpHeaders httpHeaders = new HttpHeaders();
        if (retryAfterValue != null) {
            httpHeaders.set(HttpHeaders.RETRY_AFTER, retryAfterValue);
        }
        return new org.springframework.web.reactive.function.client.ClientResponse.Headers() {
            @Override
            public java.util.OptionalLong contentLength() {
                return java.util.OptionalLong.empty();
            }

            @Override
            public java.util.Optional<org.springframework.http.MediaType> contentType() {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.List<String> header(String headerName) {
                return httpHeaders.getOrEmpty(headerName);
            }

            @Override
            public HttpHeaders asHttpHeaders() {
                return httpHeaders;
            }
        };
    }
}
