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
class RecruiteeStrategyTest {

    private RecruiteeStrategy extractor;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        WebClient webClient = WebClient.builder().build();
        // Template uses %s for slug, but in tests we point all requests to WireMock
        // by using a fixed base URL (no slug interpolation needed for the HTTP call)
        String baseUrlTemplate = wmInfo.getHttpBaseUrl() + "/%s";
        extractor = new RecruiteeStrategy(webClient, new ObjectMapper(), baseUrlTemplate);
    }

    @Test
    void supportedTypes_returnsRecruitee() {
        assertThat(extractor.supports(AtsType.RECRUITEE)).isTrue();
    }

    @Test
    void extract_validResponse_returnsJobs() {
        String json = """
                {
                  "offers": [
                    {
                      "id": 12345,
                      "title": "Backend Engineer",
                      "city": "Berlin",
                      "country": "Germany",
                      "department": "Engineering",
                      "careers_url": "https://acme.recruitee.com/o/backend-engineer",
                      "created_at": "2024-01-10T10:00:00Z",
                      "published_at": "2024-01-15T12:00:00Z"
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/acme/api/offers"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.RECRUITEE)
                .atsSlug("acme")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.totalFound()).isEqualTo(1);

        var job = result.jobs().get(0);
        assertThat(job.externalId()).isEqualTo("12345");
        assertThat(job.title()).isEqualTo("Backend Engineer");
        assertThat(job.location()).isEqualTo("Berlin, Germany");
        assertThat(job.applyUrl()).isEqualTo("https://acme.recruitee.com/o/backend-engineer");
        assertThat(job.postedDate()).isEqualTo(LocalDate.of(2024, 1, 15));
        assertThat(job.description()).isNull();
        assertThat(job.salaryMin()).isNull();
        assertThat(job.salaryMax()).isNull();
        assertThat(job.salaryCurrency()).isNull();
    }

    @Test
    void extract_multipleOffers_returnsAll() {
        String json = """
                {
                  "offers": [
                    {
                      "id": 100,
                      "title": "Job A",
                      "city": "London",
                      "country": "UK",
                      "careers_url": "https://co.recruitee.com/o/job-a",
                      "published_at": "2024-02-01T08:00:00Z"
                    },
                    {
                      "id": 200,
                      "title": "Job B",
                      "city": "Paris",
                      "country": "France",
                      "careers_url": "https://co.recruitee.com/o/job-b",
                      "published_at": "2024-02-02T09:00:00Z"
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/myco/api/offers"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.RECRUITEE)
                .atsSlug("myco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);
        assertThat(result.jobs().get(0).externalId()).isEqualTo("100");
        assertThat(result.jobs().get(1).externalId()).isEqualTo("200");
        assertThat(result.jobs().get(1).location()).isEqualTo("Paris, France");
    }

    @Test
    void extract_emptyOffers_returnsEmpty() {
        stubFor(get(urlPathMatching("/empty/api/offers"))
                .willReturn(okJson("{\"offers\": []}")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.RECRUITEE)
                .atsSlug("empty")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_notFound404_returnsEmpty() {
        stubFor(get(urlPathMatching("/gone/api/offers"))
                .willReturn(aResponse().withStatus(404)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.RECRUITEE)
                .atsSlug("gone")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_serverError500_returnsError() {
        stubFor(get(urlPathMatching("/broken/api/offers"))
                .willReturn(serverError()));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.RECRUITEE)
                .atsSlug("broken")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("500");
    }

    @Test
    void extract_cityOnly_locationIsCityOnly() {
        String json = """
                {
                  "offers": [
                    {
                      "id": 1,
                      "title": "Dev",
                      "city": "Munich",
                      "country": "",
                      "careers_url": "https://x.recruitee.com/o/dev",
                      "published_at": "2024-03-01T00:00:00Z"
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/x/api/offers"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.RECRUITEE)
                .atsSlug("x")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.jobs().get(0).location()).isEqualTo("Munich");
    }

    @Test
    void extract_countryOnly_locationIsCountryOnly() {
        String json = """
                {
                  "offers": [
                    {
                      "id": 2,
                      "title": "PM",
                      "city": "",
                      "country": "Germany",
                      "careers_url": "https://y.recruitee.com/o/pm",
                      "published_at": "2024-03-01T00:00:00Z"
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/y/api/offers"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.RECRUITEE)
                .atsSlug("y")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.jobs().get(0).location()).isEqualTo("Germany");
    }

    @Test
    void extract_noCityNoCountry_locationIsNull() {
        String json = """
                {
                  "offers": [
                    {
                      "id": 3,
                      "title": "Remote Role",
                      "city": "",
                      "country": "",
                      "careers_url": "https://z.recruitee.com/o/remote",
                      "published_at": "2024-03-01T00:00:00Z"
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/z/api/offers"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.RECRUITEE)
                .atsSlug("z")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.jobs().get(0).location()).isNull();
    }

    @Test
    void extract_nullPublishedAt_postedDateIsNull() {
        String json = """
                {
                  "offers": [
                    {
                      "id": 4,
                      "title": "Draft Job",
                      "city": "Berlin",
                      "country": "Germany",
                      "careers_url": "https://d.recruitee.com/o/draft",
                      "published_at": null
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/d/api/offers"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.RECRUITEE)
                .atsSlug("d")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).postedDate()).isNull();
    }

    @Test
    void extract_noOffersKey_returnsEmpty() {
        stubFor(get(urlPathMatching("/nokey/api/offers"))
                .willReturn(okJson("{\"something_else\": []}")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.RECRUITEE)
                .atsSlug("nokey")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }
}
