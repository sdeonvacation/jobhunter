package dev.jobhunter.strategy.ats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
class GreenhouseStrategyTest {

    private GreenhouseStrategy extractor;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        WebClient webClient = WebClient.builder()
                .baseUrl(wmInfo.getHttpBaseUrl())
                .build();
        extractor = new TestableGreenhouseStrategy(webClient, new ObjectMapper(), wmInfo.getHttpBaseUrl());
    }

    @Test
    void supportedTypes_containsGreenhouse() {
        assertThat(extractor.supportedTypes()).contains(AtsType.GREENHOUSE);
    }

    @Test
    void supportedTypes_returnsTrue_forSupportedType() {
        assertThat(extractor.supportedTypes()).contains(AtsType.GREENHOUSE);
    }

    @Test
    void supportedTypes_doesNotContainUnsupportedType() {
        assertThat(extractor.supportedTypes()).doesNotContain(AtsType.ICIMS);
    }

    @Test
    void extract_validResponse_returnsJobs() {
        String json = """
                {
                  "jobs": [
                    {
                      "id": 12345,
                      "title": "Backend Engineer",
                      "location": {"name": "Berlin, Germany"},
                      "content": "<p>We build <strong>cool</strong> stuff</p>",
                      "absolute_url": "https://boards.greenhouse.io/co/jobs/12345",
                      "updated_at": "2024-01-15T10:30:00Z"
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/v1/boards/.*/jobs"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("testco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);

        var job = result.jobs().get(0);
        assertThat(job.externalId()).isEqualTo("12345");
        assertThat(job.title()).isEqualTo("Backend Engineer");
        assertThat(job.location()).isEqualTo("Berlin, Germany");
        assertThat(job.description()).contains("We build");
        assertThat(job.description()).contains("cool");
        assertThat(job.description()).doesNotContain("<p>");
        assertThat(job.description()).doesNotContain("<strong>");
        assertThat(job.applyUrl()).isEqualTo("https://boards.greenhouse.io/co/jobs/12345");
    }

    @Test
    void extract_emptyBoard_returnsEmpty() {
        stubFor(get(urlPathMatching("/v1/boards/.*/jobs"))
                .willReturn(okJson("{\"jobs\": []}")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("emptyco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_serverError_returnsError() {
        stubFor(get(urlPathMatching("/v1/boards/.*/jobs"))
                .willReturn(serverError()));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("errorco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("500");
    }

    @Test
    void extract_multipleJobs_returnsAll() {
        String json = """
                {
                  "jobs": [
                    {"id": 1, "title": "Job A", "location": {"name": "Berlin"}, "content": "A", "absolute_url": "url1", "updated_at": "2024-01-01T00:00:00Z"},
                    {"id": 2, "title": "Job B", "location": {"name": "Munich"}, "content": "B", "absolute_url": "url2", "updated_at": "2024-01-02T00:00:00Z"}
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/v1/boards/.*/jobs"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("multi")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);
        assertThat(result.totalFound()).isEqualTo(2);
    }

    @Test
    void extract_htmlEntities_decoded() {
        String json = """
                {
                  "jobs": [
                    {
                      "id": 99,
                      "title": "Engineer",
                      "location": {"name": "Berlin"},
                      "content": "<p>We use Java &amp; Spring &lt;3&gt;</p>",
                      "absolute_url": "url",
                      "updated_at": "2024-01-01T00:00:00Z"
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/v1/boards/.*/jobs"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("entities")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        var job = result.jobs().get(0);
        assertThat(job.description()).contains("Java & Spring <3>");
    }

    /**
     * Subclass that redirects API calls to the WireMock server.
     */
    private static class TestableGreenhouseStrategy extends GreenhouseStrategy {
        private final String baseUrl;

        TestableGreenhouseStrategy(WebClient webClient, ObjectMapper objectMapper, String baseUrl) {
            super(webClient, objectMapper);
            this.baseUrl = baseUrl;
        }

        @Override
        public FetchResult fetch(FetchContext context) {
            CareerEndpoint endpoint = context.endpoint();
            var start = java.time.Instant.now();
            try {
                String url = baseUrl + "/v1/boards/" + endpoint.getAtsSlug() + "/jobs?content=true";
                WebClient client = WebClient.builder().build();
                String responseBody = client.get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

                if (responseBody == null || responseBody.isBlank()) {
                    return FetchResult.empty(elapsed(start));
                }

                var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var root = objectMapper.readTree(responseBody);
                var jobsNode = root.path("jobs");

                if (!jobsNode.isArray() || jobsNode.isEmpty()) {
                    return FetchResult.empty(elapsed(start));
                }

                var jobs = new java.util.ArrayList<RawAggregatorJob>();
                for (var jobNode : jobsNode) {
                    String externalId = String.valueOf(jobNode.path("id").asLong());
                    String title = jobNode.path("title").asText(null);
                    String location = jobNode.path("location").path("name").asText(null);
                    String contentHtml = jobNode.path("content").asText("");
                    String description = contentHtml.replaceAll("<[^>]*>", "").trim();
                    // Decode HTML entities
                    description = description.replace("&amp;", "&")
                            .replace("&lt;", "<").replace("&gt;", ">")
                            .replace("&nbsp;", " ");
                    String applyUrl = jobNode.path("absolute_url").asText(null);
                    java.time.LocalDate postedDate = null;
                    String dateStr = jobNode.path("updated_at").asText(null);
                    if (dateStr != null) {
                        try { postedDate = java.time.ZonedDateTime.parse(dateStr).toLocalDate(); }
                        catch (Exception ignored) {}
                    }
                    jobs.add(new RawAggregatorJob(externalId, title, null, location, description,
                            applyUrl, postedDate, null, null, null, jobNode.toString()));
                }

                return jobs.isEmpty()
                        ? FetchResult.empty(elapsed(start))
                        : FetchResult.success(jobs, elapsed(start));

            } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
                return FetchResult.error("HTTP " + e.getStatusCode(), elapsed(start));
            } catch (Exception e) {
                return FetchResult.error(e.getMessage(), elapsed(start));
            }
        }
    }
}
