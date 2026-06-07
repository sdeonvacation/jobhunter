package dev.jobhub.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.model.enums.AtsType;
import dev.jobhub.model.enums.ExtractionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
class PersonioExtractorTest {

    private PersonioExtractor extractor;
    private String baseUrl;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        WebClient webClient = WebClient.builder().build();
        baseUrl = wmInfo.getHttpBaseUrl();
        // Both .de and .com templates point to WireMock; slug is ignored in URL since WireMock matches on path
        String deTemplate = baseUrl + "/%s-de";
        String comTemplate = baseUrl + "/%s-com";
        extractor = new PersonioExtractor(webClient, new ObjectMapper(), deTemplate, comTemplate);
    }

    @Test
    void supportedTypes_returnsPersonio() {
        assertThat(extractor.supportedTypes()).containsExactly(AtsType.PERSONIO);
    }

    @Test
    void canExtract_validEndpoint_returnsTrue() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.PERSONIO)
                .atsSlug("acme")
                .build();
        assertThat(extractor.canExtract(endpoint)).isTrue();
    }

    @Test
    void canExtract_nullSlug_returnsFalse() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.PERSONIO)
                .atsSlug(null)
                .build();
        assertThat(extractor.canExtract(endpoint)).isFalse();
    }

    @Test
    void canExtract_blankSlug_returnsFalse() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.PERSONIO)
                .atsSlug("  ")
                .build();
        assertThat(extractor.canExtract(endpoint)).isFalse();
    }

    @Test
    void canExtract_wrongAtsType_returnsFalse() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.GREENHOUSE)
                .atsSlug("acme")
                .build();
        assertThat(extractor.canExtract(endpoint)).isFalse();
    }

    @Test
    void extract_validResponse_returnsJobs() {
        String json = """
                [
                  {
                    "id": 12345,
                    "name": "Backend Engineer",
                    "office": "Berlin",
                    "department": "Engineering",
                    "recruitingCategory": "Tech",
                    "schedule": "FULL_TIME",
                    "createdAt": "2024-03-15"
                  }
                ]
                """;
        stubFor(get(urlPathEqualTo("/acme-de/search.json"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.PERSONIO)
                .atsSlug("acme")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.totalFound()).isEqualTo(1);

        var job = result.jobs().get(0);
        assertThat(job.externalId()).isEqualTo("12345");
        assertThat(job.title()).isEqualTo("Backend Engineer");
        assertThat(job.location()).isEqualTo("Berlin");
        assertThat(job.applyUrl()).isEqualTo("https://acme.jobs.personio.de/job/12345");
        assertThat(job.postedDate()).isEqualTo(LocalDate.of(2024, 3, 15));
        assertThat(job.description()).isNull();
        assertThat(job.salaryMin()).isNull();
        assertThat(job.salaryMax()).isNull();
        assertThat(job.salaryCurrency()).isNull();
    }

    @Test
    void extract_multipleJobs_returnsAll() {
        String json = """
                [
                  {
                    "id": 100,
                    "name": "Job A",
                    "office": "Munich",
                    "createdAt": "2024-01-01"
                  },
                  {
                    "id": 200,
                    "name": "Job B",
                    "office": "Hamburg",
                    "createdAt": "2024-02-01"
                  },
                  {
                    "id": 300,
                    "name": "Job C",
                    "office": "Remote",
                    "createdAt": "2024-03-01"
                  }
                ]
                """;
        stubFor(get(urlPathEqualTo("/testco-de/search.json"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.PERSONIO)
                .atsSlug("testco")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(3);
        assertThat(result.totalFound()).isEqualTo(3);
        assertThat(result.jobs().get(0).externalId()).isEqualTo("100");
        assertThat(result.jobs().get(1).externalId()).isEqualTo("200");
        assertThat(result.jobs().get(2).externalId()).isEqualTo("300");
    }

    @Test
    void extract_emptyArray_returnsEmpty() {
        stubFor(get(urlPathEqualTo("/empty-de/search.json"))
                .willReturn(okJson("[]")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.PERSONIO)
                .atsSlug("empty")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_deReturns404_fallsBackToCom() {
        // .de returns 404
        stubFor(get(urlPathEqualTo("/fallback-de/search.json"))
                .willReturn(aResponse().withStatus(404)));

        // .com returns jobs
        String json = """
                [
                  {
                    "id": 999,
                    "name": "Fallback Job",
                    "office": "Vienna",
                    "createdAt": "2024-05-01"
                  }
                ]
                """;
        stubFor(get(urlPathEqualTo("/fallback-com/search.json"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.PERSONIO)
                .atsSlug("fallback")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).externalId()).isEqualTo("999");
        assertThat(result.jobs().get(0).title()).isEqualTo("Fallback Job");
    }

    @Test
    void extract_bothDomainsReturn404_returnsEmpty() {
        stubFor(get(urlPathEqualTo("/gone-de/search.json"))
                .willReturn(aResponse().withStatus(404)));
        stubFor(get(urlPathEqualTo("/gone-com/search.json"))
                .willReturn(aResponse().withStatus(404)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.PERSONIO)
                .atsSlug("gone")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_serverError500_returnsError() {
        stubFor(get(urlPathEqualTo("/broken-de/search.json"))
                .willReturn(serverError()));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.PERSONIO)
                .atsSlug("broken")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("500");
    }

    @Test
    void extract_rateLimited429_returnsRateLimited() {
        stubFor(get(urlPathEqualTo("/limited-de/search.json"))
                .willReturn(aResponse().withStatus(429).withBody("too many requests")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.PERSONIO)
                .atsSlug("limited")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.RATE_LIMITED);
        assertThat(result.errorMessage()).contains("Rate limited");
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_zonedDateTimeFallback_parsesCorrectly() {
        String json = """
                [
                  {
                    "id": 555,
                    "name": "Dev",
                    "office": "Zurich",
                    "createdAt": "2024-06-15T10:30:00Z"
                  }
                ]
                """;
        stubFor(get(urlPathEqualTo("/zdt-de/search.json"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.PERSONIO)
                .atsSlug("zdt")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).postedDate()).isEqualTo(LocalDate.of(2024, 6, 15));
    }

    @Test
    void extract_nullCreatedAt_returnsNullDate() {
        String json = """
                [
                  {
                    "id": 777,
                    "name": "No Date Job",
                    "office": "Paris"
                  }
                ]
                """;
        stubFor(get(urlPathEqualTo("/nodate-de/search.json"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.PERSONIO)
                .atsSlug("nodate")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).postedDate()).isNull();
    }

    @Test
    void extract_longTitle_truncatedTo500() {
        String longTitle = "A".repeat(600);
        String json = """
                [
                  {
                    "id": 888,
                    "name": "%s",
                    "office": "Somewhere"
                  }
                ]
                """.formatted(longTitle);
        stubFor(get(urlPathEqualTo("/trunc-de/search.json"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.PERSONIO)
                .atsSlug("trunc")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).title()).hasSize(500);
    }

    @Test
    void extract_nullOffice_returnsNullLocation() {
        String json = """
                [
                  {
                    "id": 444,
                    "name": "Remote Role",
                    "office": null
                  }
                ]
                """;
        stubFor(get(urlPathEqualTo("/remote-de/search.json"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.PERSONIO)
                .atsSlug("remote")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).location()).isNull();
    }

    @Test
    void extract_rawJsonStored() {
        String json = """
                [
                  {
                    "id": 111,
                    "name": "Test",
                    "office": "Berlin",
                    "department": "Engineering"
                  }
                ]
                """;
        stubFor(get(urlPathEqualTo("/raw-de/search.json"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.PERSONIO)
                .atsSlug("raw")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).rawJson()).contains("\"id\":111");
        assertThat(result.jobs().get(0).rawJson()).contains("\"department\":\"Engineering\"");
    }

    @Test
    void extract_elapsedTimeIsPositive() {
        stubFor(get(urlPathEqualTo("/time-de/search.json"))
                .willReturn(okJson("[]")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.PERSONIO)
                .atsSlug("time")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.elapsed()).isNotNull();
        assertThat(result.elapsed().isNegative()).isFalse();
    }
}
