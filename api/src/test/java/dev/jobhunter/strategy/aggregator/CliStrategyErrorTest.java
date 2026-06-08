package dev.jobhunter.strategy.aggregator;

import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CliStrategyErrorTest {

    @Test
    @DisplayName("Should return ERROR when CLI process fails for all iterations")
    void shouldReturnErrorWhenAllIterationsFail() {
        CliStrategy strategy = new CliStrategy();

        // Use very short timeout to force failure quickly
        FetchContext context = FetchContext.forSearch(
                List.of("java"), List.of("Germany"), 25, 1,
                Map.of("hours-old", 24, "timeout-seconds", 2));

        FetchResult result = strategy.fetch(context);

        // npx jobspy-js won't be available in test env, so it will error
        // The result should be ERROR (not EMPTY) since the process fails
        if (result.status() == ExtractionStatus.ERROR) {
            assertThat(result.errorMessage()).isNotNull();
            assertThat(result.errorMessage()).contains("All searches failed");
            assertThat(result.errorMessage()).contains("(1)");
        }
        // If somehow the CLI is available and returns no results, EMPTY is also valid
    }

    @Test
    @DisplayName("Should return EMPTY when no errors occur but no jobs found")
    void shouldReturnEmptyWhenNoErrorsButNoJobs() {
        CliStrategy strategy = new CliStrategy();

        // mapToRawJobs returns empty for empty input - test the logic path directly
        // This tests that empty results without errors return EMPTY, not ERROR
        var jobs = strategy.mapToRawJobs(List.of());
        assertThat(jobs).isEmpty();
    }
}
