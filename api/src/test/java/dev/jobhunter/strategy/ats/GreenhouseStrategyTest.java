package dev.jobhunter.strategy.ats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.strategy.FetchContext;
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
        extractor = new GreenhouseStrategy(webClient, new ObjectMapper(), wmInfo.getHttpBaseUrl());
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
                      "first_published": "2024-01-15T10:30:00Z"
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
        assertThat(job.postedDate()).isEqualTo(java.time.LocalDate.of(2024, 1, 15));
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
                      "first_published": "2024-01-01T00:00:00Z"
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

    @Test
    void extract_usesFirstPublished_notUpdatedAt() {
        // Regression: updated_at is Greenhouse's last-modified stamp, so using it made long-open
        // roles look freshly posted (59% of rows disagree). first_published is the real post date.
        String json = """
                {
                  "jobs": [
                    {
                      "id": 777,
                      "title": "Long-open Role",
                      "location": {"name": "Berlin"},
                      "content": "<p>x</p>",
                      "absolute_url": "https://boards.greenhouse.io/co/jobs/777",
                      "first_published": "2024-01-15T10:30:00-04:00",
                      "updated_at": "2026-09-25T08:00:00-04:00"
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/v1/boards/.*/jobs"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("regressco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).postedDate()).isEqualTo(java.time.LocalDate.of(2024, 1, 15));
    }
}
