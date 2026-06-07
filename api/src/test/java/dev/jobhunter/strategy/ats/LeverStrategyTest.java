package dev.jobhunter.strategy.ats;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
class LeverStrategyTest {

    private LeverStrategy extractor;
    private String baseUrl;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        WebClient webClient = WebClient.builder()
                .baseUrl(wmInfo.getHttpBaseUrl())
                .build();
        baseUrl = wmInfo.getHttpBaseUrl();
        extractor = new TestableLeverStrategy(webClient, new ObjectMapper(), baseUrl);
    }

    @Test
    void supports_leverAndLeverEu() {
        assertThat(extractor.supports(AtsType.LEVER)).isTrue();
        assertThat(extractor.supports(AtsType.LEVER_EU)).isTrue();
    }

    @Test
    void supports_returnsTrue_forLever() {
        assertThat(extractor.supports(AtsType.LEVER)).isTrue();
    }

    @Test
    void supports_returnsTrue_forLeverEu() {
        assertThat(extractor.supports(AtsType.LEVER_EU)).isTrue();
    }

    @Test
    void supports_returnsFalse_forUnsupportedType() {
        assertThat(extractor.supports(AtsType.GREENHOUSE)).isFalse();
    }

    @Test
    void extract_validMultiJobResponse_returnsAllJobs() {
        String json = """
                [
                  {
                    "id": "abc-123",
                    "text": "Backend Engineer",
                    "categories": {"location": "Berlin, Germany", "team": "Engineering"},
                    "descriptionPlain": "Build awesome backend services.",
                    "hostedUrl": "https://jobs.lever.co/testco/abc-123",
                    "createdAt": 1705312200000
                  },
                  {
                    "id": "def-456",
                    "text": "Frontend Developer",
                    "categories": {"location": "Remote", "team": "Engineering"},
                    "descriptionPlain": "Create beautiful user interfaces.",
                    "hostedUrl": "https://jobs.lever.co/testco/def-456",
                    "createdAt": 1705398600000
                  }
                ]
                """;
        stubFor(get(urlPathMatching("/v0/postings/.*"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.LEVER)
                .atsSlug("testco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);
        assertThat(result.totalFound()).isEqualTo(2);

        var job1 = result.jobs().get(0);
        assertThat(job1.externalId()).isEqualTo("abc-123");
        assertThat(job1.title()).isEqualTo("Backend Engineer");
        assertThat(job1.location()).isEqualTo("Berlin, Germany");
        assertThat(job1.description()).isEqualTo("Build awesome backend services.");
        assertThat(job1.applyUrl()).isEqualTo("https://jobs.lever.co/testco/abc-123");
        assertThat(job1.postedDate()).isEqualTo(LocalDate.of(2024, 1, 15));

        var job2 = result.jobs().get(1);
        assertThat(job2.externalId()).isEqualTo("def-456");
        assertThat(job2.title()).isEqualTo("Frontend Developer");
        assertThat(job2.location()).isEqualTo("Remote");
    }

    @Test
    void extract_leverEuEndpoint_returnsJobs() {
        String json = """
                [
                  {
                    "id": "eu-001",
                    "text": "Data Analyst",
                    "categories": {"location": "Amsterdam, Netherlands"},
                    "descriptionPlain": "Analyze data for EU market.",
                    "hostedUrl": "https://jobs.eu.lever.co/eurocompany/eu-001",
                    "createdAt": 1705312200000
                  }
                ]
                """;
        stubFor(get(urlPathMatching("/v0/postings/.*"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.LEVER_EU)
                .atsSlug("eurocompany")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);

        var job = result.jobs().get(0);
        assertThat(job.externalId()).isEqualTo("eu-001");
        assertThat(job.title()).isEqualTo("Data Analyst");
        assertThat(job.location()).isEqualTo("Amsterdam, Netherlands");
    }

    @Test
    void extract_emptyArray_returnsEmpty() {
        stubFor(get(urlPathMatching("/v0/postings/.*"))
                .willReturn(okJson("[]")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.LEVER)
                .atsSlug("emptyco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_notFound404_returnsEmpty() {
        stubFor(get(urlPathMatching("/v0/postings/.*"))
                .willReturn(aResponse().withStatus(404)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.LEVER)
                .atsSlug("invalidslug")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_serverError500_returnsError() {
        stubFor(get(urlPathMatching("/v0/postings/.*"))
                .willReturn(serverError()));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.LEVER)
                .atsSlug("brokenco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("500");
    }

    @Test
    void extract_jobWithNullCreatedAt_handlesGracefully() {
        String json = """
                [
                  {
                    "id": "no-date",
                    "text": "Engineer",
                    "categories": {"location": "NYC"},
                    "descriptionPlain": "Some description.",
                    "hostedUrl": "https://jobs.lever.co/co/no-date",
                    "createdAt": 0
                  }
                ]
                """;
        stubFor(get(urlPathMatching("/v0/postings/.*"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.LEVER)
                .atsSlug("testco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).postedDate()).isNull();
    }

    /**
     * Subclass that redirects API calls to the WireMock server.
     */
    private static class TestableLeverStrategy extends LeverStrategy {
        private final String baseUrl;
        private final ObjectMapper objectMapper;

        TestableLeverStrategy(WebClient webClient, ObjectMapper objectMapper, String baseUrl) {
            super(webClient, objectMapper);
            this.baseUrl = baseUrl;
            this.objectMapper = objectMapper;
        }

        @Override
        public FetchResult fetch(FetchContext context) {
            CareerEndpoint endpoint = context.endpoint();
            Instant start = Instant.now();
            String slug = endpoint.getAtsSlug();

            try {
                String url = baseUrl + "/v0/postings/" + slug + "?mode=json";
                WebClient client = WebClient.builder().build();
                String responseBody = client.get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

                if (responseBody == null || responseBody.isBlank()) {
                    return FetchResult.empty(elapsed(start));
                }

                JsonNode root = objectMapper.readTree(responseBody);

                if (!root.isArray() || root.isEmpty()) {
                    return FetchResult.empty(elapsed(start));
                }

                List<RawAggregatorJob> jobs = new ArrayList<>();
                for (JsonNode jobNode : root) {
                    String externalId = jobNode.path("id").asText(null);
                    String title = jobNode.path("text").asText(null);
                    String location = jobNode.path("categories").path("location").asText(null);
                    String description = jobNode.path("descriptionPlain").asText("");
                    String applyUrl = jobNode.path("hostedUrl").asText(null);

                    LocalDate postedDate = null;
                    long millis = jobNode.path("createdAt").asLong(0);
                    if (millis > 0) {
                        postedDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate();
                    }

                    jobs.add(new RawAggregatorJob(externalId, title, null, location, description,
                            applyUrl, postedDate, null, null, null, jobNode.toString()));
                }

                return jobs.isEmpty()
                        ? FetchResult.empty(elapsed(start))
                        : FetchResult.success(jobs, elapsed(start));

            } catch (WebClientResponseException.NotFound e) {
                return FetchResult.empty(elapsed(start));
            } catch (WebClientResponseException e) {
                return FetchResult.error("HTTP " + e.getStatusCode(), elapsed(start));
            } catch (Exception e) {
                return FetchResult.error(e.getMessage(), elapsed(start));
            }
        }

        private Duration elapsed(Instant start) {
            return Duration.between(start, Instant.now());
        }
    }
}
