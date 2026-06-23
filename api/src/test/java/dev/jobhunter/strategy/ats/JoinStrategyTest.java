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
class JoinStrategyTest {

    private JoinStrategy extractor;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        WebClient webClient = WebClient.builder().build();
        extractor = new JoinStrategy(webClient, new ObjectMapper(), wmInfo.getHttpBaseUrl());
    }

    @Test
    void supportedTypes_returnsJoin() {
        assertThat(extractor.supportedTypes()).contains(AtsType.JOIN);
    }

    @Test
    void extract_validResponse_returnsJobs() {
        String json = """
                [
                  {
                    "id": "abc-123",
                    "title": "Backend Engineer",
                    "city": "Berlin",
                    "countryCode": "DE",
                    "department": "Engineering",
                    "jobUrl": "https://join.com/companies/coolco/jobs/abc-123",
                    "createdAt": "2024-03-15T10:00:00Z"
                  },
                  {
                    "id": "def-456",
                    "title": "Frontend Developer",
                    "city": "Munich",
                    "countryCode": "DE",
                    "department": "Engineering",
                    "jobUrl": "https://join.com/companies/coolco/jobs/def-456",
                    "createdAt": "2024-03-10T08:30:00Z"
                  }
                ]
                """;
        stubFor(get(urlPathMatching("/v1/companies/.*/jobs"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("coolco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);
        assertThat(result.totalFound()).isEqualTo(2);

        var job = result.jobs().get(0);
        assertThat(job.externalId()).isEqualTo("abc-123");
        assertThat(job.title()).isEqualTo("Backend Engineer");
        assertThat(job.location()).isEqualTo("Berlin, DE");
        assertThat(job.applyUrl()).isEqualTo("https://join.com/companies/coolco/jobs/abc-123");
        assertThat(job.postedDate()).isEqualTo(LocalDate.of(2024, 3, 15));
        assertThat(job.description()).isNull();
        assertThat(job.salaryMin()).isNull();
        assertThat(job.salaryMax()).isNull();
        assertThat(job.salaryCurrency()).isNull();
    }

    @Test
    void extract_cityOnly_locationIsCityOnly() {
        String json = """
                [
                  {
                    "id": "x1",
                    "title": "Designer",
                    "city": "Hamburg",
                    "countryCode": "",
                    "jobUrl": "https://join.com/companies/co/jobs/x1",
                    "createdAt": "2024-01-01T00:00:00Z"
                  }
                ]
                """;
        stubFor(get(urlPathMatching("/v1/companies/.*/jobs"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("co")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.jobs().get(0).location()).isEqualTo("Hamburg");
    }

    @Test
    void extract_countryCodeOnly_locationIsCountryCode() {
        String json = """
                [
                  {
                    "id": "x2",
                    "title": "PM",
                    "city": "",
                    "countryCode": "US",
                    "jobUrl": "https://join.com/companies/co/jobs/x2",
                    "createdAt": "2024-02-01T00:00:00Z"
                  }
                ]
                """;
        stubFor(get(urlPathMatching("/v1/companies/.*/jobs"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("co")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.jobs().get(0).location()).isEqualTo("US");
    }

    @Test
    void extract_emptyArray_returnsEmpty() {
        stubFor(get(urlPathMatching("/v1/companies/.*/jobs"))
                .willReturn(okJson("[]")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("empty-co")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_404_returnsEmpty() {
        stubFor(get(urlPathMatching("/v1/companies/.*/jobs"))
                .willReturn(aResponse().withStatus(404)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("nonexistent")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_500_returnsError() {
        stubFor(get(urlPathMatching("/v1/companies/.*/jobs"))
                .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("broken-co")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("500");
    }

    @Test
    void extract_invalidJson_returnsError() {
        stubFor(get(urlPathMatching("/v1/companies/.*/jobs"))
                .willReturn(okJson("not valid json {")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("bad-json")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
    }

    @Test
    void extract_nullCreatedAt_postedDateIsNull() {
        String json = """
                [
                  {
                    "id": "no-date",
                    "title": "Role",
                    "city": "Berlin",
                    "countryCode": "DE",
                    "jobUrl": "https://join.com/companies/co/jobs/no-date"
                  }
                ]
                """;
        stubFor(get(urlPathMatching("/v1/companies/.*/jobs"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("co")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.jobs().get(0).postedDate()).isNull();
    }

    @Test
    void extract_responseIsObject_returnsEmpty() {
        // API returns object instead of array - should handle gracefully
        stubFor(get(urlPathMatching("/v1/companies/.*/jobs"))
                .willReturn(okJson("{\"error\": \"not found\"}")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("weird-co")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
    }
}
