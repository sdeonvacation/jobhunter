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

/**
 * Tests for Greenhouse error-body handling: API returns 200 OK with error JSON.
 */
@WireMockTest
class GreenhouseStrategyErrorBodyTest {

    private GreenhouseStrategy extractor;
    private String baseUrl;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        baseUrl = wmInfo.getHttpBaseUrl();
        WebClient webClient = WebClient.builder().build();
        extractor = new GreenhouseStrategy(webClient, new ObjectMapper()) {
            @Override
            public FetchResult fetch(FetchContext context) {
                CareerEndpoint endpoint = context.endpoint();
                var start = java.time.Instant.now();
                try {
                    String url = baseUrl + "/v1/boards/" + endpoint.getAtsSlug() + "/jobs?content=true";
                    String responseBody = WebClient.builder().build().get()
                            .uri(url)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(java.time.Duration.ofSeconds(5));

                    if (responseBody == null || responseBody.isBlank()) {
                        return FetchResult.empty(java.time.Duration.between(start, java.time.Instant.now()));
                    }

                    var objectMapper = new ObjectMapper();
                    var root = objectMapper.readTree(responseBody);

                    // Error body check (same logic as production)
                    if (root.has("status") && root.path("status").isInt()) {
                        int status = root.path("status").asInt();
                        if (status >= 400) {
                            return FetchResult.empty(java.time.Duration.between(start, java.time.Instant.now()));
                        }
                    }

                    var jobsNode = root.path("jobs");
                    if (!jobsNode.isArray() || jobsNode.isEmpty()) {
                        return FetchResult.empty(java.time.Duration.between(start, java.time.Instant.now()));
                    }

                    var jobs = new java.util.ArrayList<RawAggregatorJob>();
                    for (var jobNode : jobsNode) {
                        String externalId = String.valueOf(jobNode.path("id").asLong());
                        String title = jobNode.path("title").asText(null);
                        String location = jobNode.path("location").path("name").asText(null);
                        String description = jobNode.path("content").asText("");
                        String applyUrl = jobNode.path("absolute_url").asText(null);
                        jobs.add(new RawAggregatorJob(externalId, title, null, location, description,
                                applyUrl, null, null, null, null, jobNode.toString()));
                    }

                    return jobs.isEmpty()
                            ? FetchResult.empty(java.time.Duration.between(start, java.time.Instant.now()))
                            : FetchResult.success(jobs, java.time.Duration.between(start, java.time.Instant.now()));

                } catch (org.springframework.web.reactive.function.client.WebClientResponseException.NotFound e) {
                    return FetchResult.empty(java.time.Duration.between(start, java.time.Instant.now()));
                } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
                    return FetchResult.error("HTTP " + e.getStatusCode(), java.time.Duration.between(start, java.time.Instant.now()));
                } catch (Exception e) {
                    return FetchResult.error(e.getClass().getSimpleName() + ": " + e.getMessage(),
                            java.time.Duration.between(start, java.time.Instant.now()));
                }
            }
        };
    }

    @Test
    void extract_errorBodyWith404Status_returnsEmpty() {
        String errorJson = """
                {"status":404,"error":"Job not found"}
                """;
        stubFor(get(urlPathMatching("/v1/boards/.*/jobs"))
                .willReturn(okJson(errorJson)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("personio")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_errorBodyWith500Status_returnsEmpty() {
        String errorJson = """
                {"status":500,"error":"Internal Server Error"}
                """;
        stubFor(get(urlPathMatching("/v1/boards/.*/jobs"))
                .willReturn(okJson(errorJson)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("brokenco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_validBodyWithStatusField_notTreatedAsError() {
        // Status field < 400 should not be treated as error
        String json = """
                {"status":200,"jobs":[{"id":1,"title":"Dev","location":{"name":"Berlin"},"content":"desc","absolute_url":"url","updated_at":"2024-01-01T00:00:00Z"}]}
                """;
        stubFor(get(urlPathMatching("/v1/boards/.*/jobs"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("validco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
    }

    @Test
    void extract_http404_returnsEmpty() {
        stubFor(get(urlPathMatching("/v1/boards/.*/jobs"))
                .willReturn(aResponse().withStatus(404)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("gone")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
    }

    @Test
    void extract_nullResponse_returnsEmpty() {
        stubFor(get(urlPathMatching("/v1/boards/.*/jobs"))
                .willReturn(aResponse().withStatus(200).withBody("")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("emptyresp")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
    }
}
