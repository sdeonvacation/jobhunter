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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
class BambooHrExtractorTest {

    private BambooHrExtractor extractor;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        WebClient webClient = WebClient.builder().build();
        // Template uses %s for slug but in tests we hit WireMock directly
        // so we use a fixed base URL (slug is ignored in URL construction for test)
        String baseUrl = wmInfo.getHttpBaseUrl() + "/%s";
        extractor = new BambooHrExtractor(webClient, new ObjectMapper(), baseUrl);
    }

    @Test
    void supportedTypes_returnsBambooHr() {
        assertThat(extractor.supportedTypes()).containsExactly(AtsType.BAMBOOHR);
    }

    @Test
    void canExtract_validEndpoint_returnsTrue() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BAMBOOHR)
                .atsSlug("acme")
                .build();
        assertThat(extractor.canExtract(endpoint)).isTrue();
    }

    @Test
    void canExtract_nullSlug_returnsFalse() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BAMBOOHR)
                .atsSlug(null)
                .build();
        assertThat(extractor.canExtract(endpoint)).isFalse();
    }

    @Test
    void canExtract_blankSlug_returnsFalse() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BAMBOOHR)
                .atsSlug("  ")
                .build();
        assertThat(extractor.canExtract(endpoint)).isFalse();
    }

    @Test
    void canExtract_wrongAtsType_returnsFalse() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.LEVER)
                .atsSlug("acme")
                .build();
        assertThat(extractor.canExtract(endpoint)).isFalse();
    }

    @Test
    void extract_validResponse_returnsJobs() {
        String json = """
                {
                  "result": [
                    {
                      "id": "123",
                      "jobOpeningName": "Senior Engineer",
                      "location": {
                        "city": "Berlin",
                        "state": "",
                        "country": "Germany"
                      },
                      "departmentLabel": "Engineering"
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/acme/careers/list"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BAMBOOHR)
                .atsSlug("acme")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.totalFound()).isEqualTo(1);

        var job = result.jobs().get(0);
        assertThat(job.externalId()).isEqualTo("123");
        assertThat(job.title()).isEqualTo("Senior Engineer");
        assertThat(job.location()).isEqualTo("Berlin, Germany");
        assertThat(job.applyUrl()).contains("/acme/careers/123");
        assertThat(job.rawJson()).isNotBlank();
        assertThat(job.description()).isNull();
        assertThat(job.salaryMin()).isNull();
        assertThat(job.salaryMax()).isNull();
        assertThat(job.salaryCurrency()).isNull();
        assertThat(job.postedDate()).isNull();
    }

    @Test
    void extract_multipleJobs_returnsAll() {
        String json = """
                {
                  "result": [
                    {
                      "id": "1",
                      "jobOpeningName": "Job A",
                      "location": { "city": "Munich", "country": "Germany" }
                    },
                    {
                      "id": "2",
                      "jobOpeningName": "Job B",
                      "location": { "city": "London", "country": "UK" }
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/multi/careers/list"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BAMBOOHR)
                .atsSlug("multi")
                .build();

        var result = extractor.extract(endpoint);
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);
        assertThat(result.jobs().get(0).title()).isEqualTo("Job A");
        assertThat(result.jobs().get(1).title()).isEqualTo("Job B");
    }

    @Test
    void extract_locationCityOnly_returnsCityOnly() {
        String json = """
                {
                  "result": [
                    {
                      "id": "10",
                      "jobOpeningName": "Dev",
                      "location": { "city": "Berlin", "country": "" }
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/cityonly/careers/list"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BAMBOOHR)
                .atsSlug("cityonly")
                .build();

        var result = extractor.extract(endpoint);
        assertThat(result.jobs().get(0).location()).isEqualTo("Berlin");
    }

    @Test
    void extract_locationCountryOnly_returnsCountryOnly() {
        String json = """
                {
                  "result": [
                    {
                      "id": "11",
                      "jobOpeningName": "Dev",
                      "location": { "city": "", "country": "Germany" }
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/countryonly/careers/list"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BAMBOOHR)
                .atsSlug("countryonly")
                .build();

        var result = extractor.extract(endpoint);
        assertThat(result.jobs().get(0).location()).isEqualTo("Germany");
    }

    @Test
    void extract_locationMissing_returnsNullLocation() {
        String json = """
                {
                  "result": [
                    {
                      "id": "12",
                      "jobOpeningName": "Dev"
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/noloc/careers/list"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BAMBOOHR)
                .atsSlug("noloc")
                .build();

        var result = extractor.extract(endpoint);
        assertThat(result.jobs().get(0).location()).isNull();
    }

    @Test
    void extract_emptyResult_returnsEmpty() {
        stubFor(get(urlPathMatching("/empty/careers/list"))
                .willReturn(okJson("{\"result\": []}")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BAMBOOHR)
                .atsSlug("empty")
                .build();

        var result = extractor.extract(endpoint);
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_notFound404_returnsEmpty() {
        stubFor(get(urlPathMatching("/gone/careers/list"))
                .willReturn(aResponse().withStatus(404)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BAMBOOHR)
                .atsSlug("gone")
                .build();

        var result = extractor.extract(endpoint);
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_serverError500_returnsError() {
        stubFor(get(urlPathMatching("/broken/careers/list"))
                .willReturn(serverError()));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BAMBOOHR)
                .atsSlug("broken")
                .build();

        var result = extractor.extract(endpoint);
        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("500");
    }

    @Test
    void extract_nullResponseBody_returnsEmpty() {
        stubFor(get(urlPathMatching("/nullbody/careers/list"))
                .willReturn(aResponse().withStatus(200).withBody("")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BAMBOOHR)
                .atsSlug("nullbody")
                .build();

        var result = extractor.extract(endpoint);
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
    }
}
