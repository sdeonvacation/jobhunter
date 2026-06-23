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
class BreezyStrategyTest {

    private BreezyStrategy extractor;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        WebClient webClient = WebClient.builder().build();
        String baseUrl = wmInfo.getHttpBaseUrl() + "/%s";
        extractor = new BreezyStrategy(webClient, new ObjectMapper(), baseUrl);
    }

    @Test
    void supportedTypes_returnsBreezy() {
        assertThat(extractor.supportedTypes()).contains(AtsType.BREEZY);
    }

    @Test
    void extract_validResponse_returnsJobs() {
        String json = """
                [
                  {
                    "_id": "abc123",
                    "name": "Backend Developer",
                    "location": {
                      "city": "Berlin",
                      "country": "Germany"
                    },
                    "department": "Engineering",
                    "url": "https://coolco.breezy.hr/p/abc123"
                  }
                ]
                """;
        stubFor(get(urlPathMatching("/coolco/json"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BREEZY)
                .atsSlug("coolco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.totalFound()).isEqualTo(1);

        var job = result.jobs().get(0);
        assertThat(job.externalId()).isEqualTo("abc123");
        assertThat(job.title()).isEqualTo("Backend Developer");
        assertThat(job.location()).isEqualTo("Berlin, Germany");
        assertThat(job.applyUrl()).isEqualTo("https://coolco.breezy.hr/p/abc123");
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
                [
                  {
                    "_id": "id1",
                    "name": "Job A",
                    "location": { "city": "Munich", "country": "Germany" },
                    "url": "https://co.breezy.hr/p/id1"
                  },
                  {
                    "_id": "id2",
                    "name": "Job B",
                    "location": { "city": "London", "country": "UK" },
                    "url": "https://co.breezy.hr/p/id2"
                  }
                ]
                """;
        stubFor(get(urlPathMatching("/multi/json"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BREEZY)
                .atsSlug("multi")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);
        assertThat(result.jobs().get(0).title()).isEqualTo("Job A");
        assertThat(result.jobs().get(1).title()).isEqualTo("Job B");
    }

    @Test
    void extract_locationCityOnly_returnsCityOnly() {
        String json = """
                [
                  {
                    "_id": "x1",
                    "name": "Dev",
                    "location": { "city": "Berlin", "country": "" },
                    "url": "https://co.breezy.hr/p/x1"
                  }
                ]
                """;
        stubFor(get(urlPathMatching("/cityonly/json"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BREEZY)
                .atsSlug("cityonly")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.jobs().get(0).location()).isEqualTo("Berlin");
    }

    @Test
    void extract_locationCountryOnly_returnsCountryOnly() {
        String json = """
                [
                  {
                    "_id": "x2",
                    "name": "Dev",
                    "location": { "city": "", "country": "Germany" },
                    "url": "https://co.breezy.hr/p/x2"
                  }
                ]
                """;
        stubFor(get(urlPathMatching("/countryonly/json"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BREEZY)
                .atsSlug("countryonly")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.jobs().get(0).location()).isEqualTo("Germany");
    }

    @Test
    void extract_locationMissing_returnsNullLocation() {
        String json = """
                [
                  {
                    "_id": "x3",
                    "name": "Dev",
                    "url": "https://co.breezy.hr/p/x3"
                  }
                ]
                """;
        stubFor(get(urlPathMatching("/noloc/json"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BREEZY)
                .atsSlug("noloc")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.jobs().get(0).location()).isNull();
    }

    @Test
    void extract_emptyArray_returnsEmpty() {
        stubFor(get(urlPathMatching("/empty/json"))
                .willReturn(okJson("[]")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BREEZY)
                .atsSlug("empty")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_notFound404_returnsEmpty() {
        stubFor(get(urlPathMatching("/gone/json"))
                .willReturn(aResponse().withStatus(404)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BREEZY)
                .atsSlug("gone")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_serverError500_returnsError() {
        stubFor(get(urlPathMatching("/broken/json"))
                .willReturn(serverError()));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BREEZY)
                .atsSlug("broken")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("500");
    }

    @Test
    void extract_nullResponseBody_returnsEmpty() {
        stubFor(get(urlPathMatching("/nullbody/json"))
                .willReturn(aResponse().withStatus(200).withBody("")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.BREEZY)
                .atsSlug("nullbody")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
    }
}
