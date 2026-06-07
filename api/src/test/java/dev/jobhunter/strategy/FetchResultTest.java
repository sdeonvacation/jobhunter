package dev.jobhunter.strategy;

import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.ExtractionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FetchResultTest {

    @Test
    void success_setsStatusAndJobCount() {
        var job = new RawAggregatorJob("ext-1", "Engineer", "Acme",
                "Berlin", "desc", "http://apply", LocalDate.now(),
                BigDecimal.valueOf(70000), BigDecimal.valueOf(90000), "EUR", "{}");
        var result = FetchResult.success(List.of(job), Duration.ofMillis(150));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.totalFound()).isEqualTo(1);
        assertThat(result.errorMessage()).isNull();
        assertThat(result.elapsed()).isEqualTo(Duration.ofMillis(150));
    }

    @Test
    void empty_returnsNoJobs() {
        var result = FetchResult.empty(Duration.ofMillis(50));

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
        assertThat(result.totalFound()).isZero();
    }

    @Test
    void error_setsMessageAndStatus() {
        var result = FetchResult.error("Connection timeout", Duration.ofMillis(5000));

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).isEqualTo("Connection timeout");
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void rateLimited_setsCorrectStatus() {
        var result = FetchResult.rateLimited(Duration.ofMillis(200));

        assertThat(result.status()).isEqualTo(ExtractionStatus.RATE_LIMITED);
        assertThat(result.errorMessage()).contains("Rate limited");
    }

    @Test
    void protectedEndpoint_setsCorrectStatus() {
        var result = FetchResult.protectedEndpoint(Duration.ofMillis(100));

        assertThat(result.status()).isEqualTo(ExtractionStatus.PROTECTED);
        assertThat(result.errorMessage()).contains("Protected");
        assertThat(result.jobs()).isEmpty();
    }
}

class FetchContextTest {

    @Test
    void forEndpoint_setsDefaults() {
        var endpoint = CareerEndpoint.builder().url("https://example.com").build();
        var ctx = FetchContext.forEndpoint(endpoint);

        assertThat(ctx.endpoint()).isEqualTo(endpoint);
        assertThat(ctx.keywords()).isNull();
        assertThat(ctx.locations()).isNull();
        assertThat(ctx.maxResults()).isEqualTo(200);
        assertThat(ctx.maxPages()).isEqualTo(10);
        assertThat(ctx.config()).isEmpty();
    }

    @Test
    void forSearch_setsAllFields() {
        var ctx = FetchContext.forSearch(
                List.of("java", "spring"), List.of("Berlin"),
                100, 5, Map.of("key", "val"));

        assertThat(ctx.endpoint()).isNull();
        assertThat(ctx.keywords()).containsExactly("java", "spring");
        assertThat(ctx.locations()).containsExactly("Berlin");
        assertThat(ctx.maxResults()).isEqualTo(100);
        assertThat(ctx.maxPages()).isEqualTo(5);
        assertThat(ctx.config()).containsEntry("key", "val");
    }
}
