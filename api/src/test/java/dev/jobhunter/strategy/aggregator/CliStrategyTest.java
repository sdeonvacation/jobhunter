package dev.jobhunter.strategy.aggregator;

import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CliStrategyTest {

    private CliStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new CliStrategy();
    }

    @Test
    @DisplayName("name() returns jobspy-cli")
    void nameReturnsJobspyCli() {
        assertThat(strategy.name()).isEqualTo("jobspy-cli");
    }

    @Test
    @DisplayName("supports INDEED AtsType")
    void supportsIndeed() {
        assertThat(strategy.supports(AtsType.INDEED)).isTrue();
        assertThat(strategy.supports(AtsType.LINKEDIN)).isFalse();
        assertThat(strategy.supports(AtsType.GREENHOUSE)).isFalse();
    }

    @Nested
    @DisplayName("fetch")
    class FetchTests {

        @Test
        @DisplayName("Should return empty when keywords are null")
        void shouldReturnEmptyForNullKeywords() {
            FetchContext context = FetchContext.forSearch(null, List.of("Germany"), 25, 1, Map.of());
            FetchResult result = strategy.fetch(context);
            assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("Should return empty when keywords are empty")
        void shouldReturnEmptyForEmptyKeywords() {
            FetchContext context = FetchContext.forSearch(List.of(), List.of("Germany"), 25, 1, Map.of());
            FetchResult result = strategy.fetch(context);
            assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("Should return empty when locations are null")
        void shouldReturnEmptyForNullLocations() {
            FetchContext context = FetchContext.forSearch(List.of("java"), null, 25, 1, Map.of());
            FetchResult result = strategy.fetch(context);
            assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("Should return empty when locations are empty")
        void shouldReturnEmptyForEmptyLocations() {
            FetchContext context = FetchContext.forSearch(List.of("java"), List.of(), 25, 1, Map.of());
            FetchResult result = strategy.fetch(context);
            assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        }

        @Test
        @DisplayName("Should not throw on process execution - returns valid FetchResult")
        void shouldNotThrowOnProcessExecution() {
            FetchContext context = FetchContext.forSearch(
                    List.of("java"), List.of("Germany"), 25, 1,
                    Map.of("hours-old", 24, "timeout-seconds", 10));

            FetchResult result = strategy.fetch(context);

            // Never throws, always returns a valid result regardless of CLI availability
            assertThat(result).isNotNull();
            assertThat(result.status()).isNotNull();
            assertThat(result.elapsed()).isNotNull();
        }
    }

    @Nested
    @DisplayName("mapToRawJobs")
    class MapToRawJobsTests {

        @Test
        @DisplayName("Should map jobspy results to RawAggregatorJob")
        void shouldMapResults() {
            List<CliStrategy.JobspyResult> results = List.of(
                    new CliStrategy.JobspyResult("in-abc123", "indeed",
                            "https://de.indeed.com/viewjob?jk=abc123", null,
                            "Backend Engineer", "SAP", "Berlin", "2026-06-07", false, "Java Spring Boot"),
                    new CliStrategy.JobspyResult("in-def456", "indeed",
                            "https://de.indeed.com/viewjob?jk=def456", null,
                            "Kotlin Dev", "Siemens", "Munich", null, true, "Kotlin developer")
            );

            List<RawAggregatorJob> jobs = strategy.mapToRawJobs(results);

            assertThat(jobs).hasSize(2);
            assertThat(jobs.get(0).externalId()).isEqualTo("in-abc123");
            assertThat(jobs.get(0).title()).isEqualTo("Backend Engineer");
            assertThat(jobs.get(0).companyName()).isEqualTo("SAP");
            assertThat(jobs.get(0).location()).isEqualTo("Berlin");
            assertThat(jobs.get(0).description()).isEqualTo("Java Spring Boot");
            assertThat(jobs.get(0).applyUrl()).isEqualTo("https://de.indeed.com/viewjob?jk=abc123");
            assertThat(jobs.get(1).externalId()).isEqualTo("in-def456");
            assertThat(jobs.get(1).companyName()).isEqualTo("Siemens");
        }

        @Test
        @DisplayName("Should skip results with blank title")
        void shouldSkipBlankTitle() {
            List<CliStrategy.JobspyResult> results = List.of(
                    new CliStrategy.JobspyResult("in-1", "indeed", "url", null,
                            "", "Company", "Berlin", null, false, "desc")
            );

            assertThat(strategy.mapToRawJobs(results)).isEmpty();
        }

        @Test
        @DisplayName("Should skip results with blank company")
        void shouldSkipBlankCompany() {
            List<CliStrategy.JobspyResult> results = List.of(
                    new CliStrategy.JobspyResult("in-1", "indeed", "url", null,
                            "Title", "", "Berlin", null, false, "desc")
            );

            assertThat(strategy.mapToRawJobs(results)).isEmpty();
        }

        @Test
        @DisplayName("Should handle null or empty list")
        void shouldHandleNullOrEmpty() {
            assertThat(strategy.mapToRawJobs(null)).isEmpty();
            assertThat(strategy.mapToRawJobs(List.of())).isEmpty();
        }

        @Test
        @DisplayName("Should parse valid date_posted")
        void shouldParseDate() {
            List<CliStrategy.JobspyResult> results = List.of(
                    new CliStrategy.JobspyResult("in-1", "indeed", "url", null,
                            "Dev", "Co", "Berlin", "2026-06-07", false, "desc")
            );

            List<RawAggregatorJob> jobs = strategy.mapToRawJobs(results);
            assertThat(jobs.get(0).postedDate()).isNotNull();
            assertThat(jobs.get(0).postedDate().toString()).isEqualTo("2026-06-07");
        }

        @Test
        @DisplayName("Should handle invalid date gracefully")
        void shouldHandleInvalidDate() {
            List<CliStrategy.JobspyResult> results = List.of(
                    new CliStrategy.JobspyResult("in-1", "indeed", "url", null,
                            "Dev", "Co", "Berlin", "not-a-date", false, "desc")
            );

            List<RawAggregatorJob> jobs = strategy.mapToRawJobs(results);
            assertThat(jobs.get(0).postedDate()).isNull();
        }
    }

    @Nested
    @DisplayName("extractExternalId")
    class ExtractExternalIdTests {

        @Test
        @DisplayName("Should prefer structured ID from jobspy-js")
        void shouldPreferStructuredId() {
            String id = strategy.extractExternalId("in-6d3675aae7316bb9", "https://de.indeed.com/viewjob?jk=abc123");
            assertThat(id).isEqualTo("in-6d3675aae7316bb9");
        }

        @Test
        @DisplayName("Should fallback to jk param when id is null")
        void shouldFallbackToJkParam() {
            String id = strategy.extractExternalId(null, "https://de.indeed.com/viewjob?jk=abc123&from=search");
            assertThat(id).isEqualTo("abc123");
        }

        @Test
        @DisplayName("Should fallback to jk param when id is blank")
        void shouldFallbackToJkParamWhenBlank() {
            String id = strategy.extractExternalId("", "https://indeed.com/viewjob?from=search&jk=deadbeef42&tk=xyz");
            assertThat(id).isEqualTo("deadbeef42");
        }

        @Test
        @DisplayName("Should fallback to URL hash when no jk param")
        void shouldFallbackToHashWhenNoJk() {
            String url = "https://indeed.com/jobs/some-other-format/12345";
            String id = strategy.extractExternalId(null, url);
            assertThat(id).isEqualTo(Integer.toHexString(url.hashCode()));
        }

        @Test
        @DisplayName("Should produce consistent hash for same URL")
        void shouldProduceConsistentHash() {
            String url = "https://indeed.com/jobs/12345";
            String id1 = strategy.extractExternalId(null, url);
            String id2 = strategy.extractExternalId(null, url);
            assertThat(id1).isEqualTo(id2);
        }

        @Test
        @DisplayName("Should handle null URL with null id")
        void shouldHandleNullUrl() {
            String id = strategy.extractExternalId(null, null);
            assertThat(id).isEqualTo(Integer.toHexString("".hashCode()));
        }
    }
}
