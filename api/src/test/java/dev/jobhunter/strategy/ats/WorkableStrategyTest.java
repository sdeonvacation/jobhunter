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

import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
class WorkableStrategyTest {

    private WorkableStrategy extractor;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        WebClient webClient = WebClient.builder().build();
        extractor = new WorkableStrategy(webClient, new ObjectMapper(), wmInfo.getHttpBaseUrl());
    }

    @Test
    void supportedTypes_returnsWorkable() {
        assertThat(extractor.supportedTypes()).contains(AtsType.WORKABLE);
    }

    @Test
    void extract_validResponse_returnsJobs() {
        String json = """
                {
                  "jobs": [
                    {
                      "id": "12345",
                      "title": "Senior Backend Engineer",
                      "shortcode": "ABC123",
                      "department": "Engineering",
                      "url": "https://apply.workable.com/acme/j/ABC123/",
                      "city": "Berlin",
                      "state": "Berlin",
                      "country": "Germany",
                      "created_at": "2024-03-15"
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/api/v1/widget/accounts/.*"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.WORKABLE)
                .atsSlug("acme")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.totalFound()).isEqualTo(1);

        var job = result.jobs().get(0);
        assertThat(job.externalId()).isEqualTo("ABC123");
        assertThat(job.title()).isEqualTo("Senior Backend Engineer");
        assertThat(job.location()).isEqualTo("Berlin, Berlin, Germany");
        assertThat(job.applyUrl()).isEqualTo("https://apply.workable.com/acme/j/ABC123/");
        assertThat(job.postedDate()).isEqualTo(LocalDate.of(2024, 3, 15));
        assertThat(job.description()).isNull();
        assertThat(job.rawJson()).contains("ABC123");
    }

    @Test
    void extract_multipleJobs_returnsAll() {
        String json = """
                {
                  "jobs": [
                    {
                      "id": "1",
                      "title": "Job A",
                      "shortcode": "AAA111",
                      "city": "London",
                      "state": "",
                      "country": "UK",
                      "created_at": "2024-01-01"
                    },
                    {
                      "id": "2",
                      "title": "Job B",
                      "shortcode": "BBB222",
                      "city": "Remote",
                      "state": "",
                      "country": "",
                      "created_at": "2024-02-15"
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/api/v1/widget/accounts/.*"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.WORKABLE)
                .atsSlug("multi")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);

        assertThat(result.jobs().get(0).externalId()).isEqualTo("AAA111");
        assertThat(result.jobs().get(0).location()).isEqualTo("London, UK");
        assertThat(result.jobs().get(1).externalId()).isEqualTo("BBB222");
        assertThat(result.jobs().get(1).location()).isEqualTo("Remote");
    }

    @Test
    void extract_emptyJobsList_returnsEmpty() {
        stubFor(get(urlPathMatching("/api/v1/widget/accounts/.*"))
                .willReturn(okJson("{\"jobs\": []}")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.WORKABLE)
                .atsSlug("emptyco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_notFound404_returnsEmpty() {
        stubFor(get(urlPathMatching("/api/v1/widget/accounts/.*"))
                .willReturn(aResponse().withStatus(404)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.WORKABLE)
                .atsSlug("nosuchco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_serverError500_returnsError() {
        stubFor(get(urlPathMatching("/api/v1/widget/accounts/.*"))
                .willReturn(serverError()));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.WORKABLE)
                .atsSlug("brokenco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("500");
    }

    @Test
    void extract_jobWithoutShortcode_isSkipped() {
        String json = """
                {
                  "jobs": [
                    {
                      "id": "1",
                      "title": "Valid Job",
                      "shortcode": "VALID1",
                      "city": "NYC",
                      "state": "NY",
                      "country": "US",
                      "created_at": "2024-05-01"
                    },
                    {
                      "id": "2",
                      "title": "Invalid Job",
                      "city": "London",
                      "state": "",
                      "country": "UK",
                      "created_at": "2024-05-02"
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/api/v1/widget/accounts/.*"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.WORKABLE)
                .atsSlug("skiptest")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).externalId()).isEqualTo("VALID1");
    }

    @Test
    void extract_isoDateTimeFormat_parsesCorrectly() {
        String json = """
                {
                  "jobs": [
                    {
                      "id": "1",
                      "title": "Engineer",
                      "shortcode": "ENG001",
                      "city": "Paris",
                      "state": "",
                      "country": "France",
                      "created_at": "2024-06-20T14:30:00Z"
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/api/v1/widget/accounts/.*"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.WORKABLE)
                .atsSlug("dateco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).postedDate()).isEqualTo(LocalDate.of(2024, 6, 20));
    }

    @Test
    void extract_detailsQueryParam_isSent() {
        stubFor(get(urlPathMatching("/api/v1/widget/accounts/.*"))
                .withQueryParam("details", equalTo("true"))
                .willReturn(okJson("{\"jobs\": []}")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.WORKABLE)
                .atsSlug("paramco")
                .build();

        extractor.fetch(FetchContext.forEndpoint(endpoint));

        verify(getRequestedFor(urlPathMatching("/api/v1/widget/accounts/paramco"))
                .withQueryParam("details", equalTo("true")));
    }

    @Test
    void extract_longTitle_isTruncated() {
        String longTitle = "A".repeat(600);
        String json = """
                {
                  "jobs": [
                    {
                      "id": "1",
                      "title": "%s",
                      "shortcode": "TRUNC1",
                      "city": "Berlin",
                      "state": "",
                      "country": "Germany",
                      "created_at": "2024-01-01"
                    }
                  ]
                }
                """.formatted(longTitle);
        stubFor(get(urlPathMatching("/api/v1/widget/accounts/.*"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.WORKABLE)
                .atsSlug("truncco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).title()).hasSize(500);
    }

    @Test
    void extract_locationCityOnly_noDanglingComma() {
        String json = """
                {
                  "jobs": [
                    {
                      "id": "1",
                      "title": "Dev",
                      "shortcode": "DEV01",
                      "city": "Amsterdam",
                      "state": "",
                      "country": "",
                      "created_at": "2024-01-01"
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/api/v1/widget/accounts/.*"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.WORKABLE)
                .atsSlug("loctest")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.jobs().get(0).location()).isEqualTo("Amsterdam");
    }
}
