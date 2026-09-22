package dev.jobhunter.strategy.ats;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.RawAggregatorJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
class JoinStrategyTest {

    private static final String TWO_JOBS = """
            [
              {
                "id": 16718276,
                "idParam": "16718276-senior-ai-product-engineer",
                "title": "Backend Engineer ",
                "city": {"cityName": "Berlin", "countryName": "Germany"},
                "country": {"iso3166": "DE"},
                "workplaceType": "REMOTE",
                "employmentType": {"name": "Full-time"},
                "createdAt": "2024-03-15T10:00:00.000Z"
              },
              {
                "id": 16718277,
                "idParam": "16718277-frontend-developer",
                "title": "Frontend Developer",
                "city": {"cityName": "Munich", "countryName": "Germany"},
                "country": {"iso3166": "DE"},
                "createdAt": "2024-03-10T08:30:00Z"
              }
            ]
            """;

    private JoinStrategy extractor;
    private String baseUrl;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        WebClient webClient = WebClient.builder().build();
        baseUrl = wmInfo.getHttpBaseUrl();
        extractor = new JoinStrategy(webClient, new ObjectMapper(), baseUrl);
    }

    @Test
    void supportedTypes_returnsJoin() {
        assertThat(extractor.supportedTypes()).contains(AtsType.JOIN);
    }

    @Test
    void extract_validResponse_returnsJobs() {
        stubCompanyPage("coolco", pageJson(TWO_JOBS, 1, 1));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("coolco")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);
        assertThat(result.totalFound()).isEqualTo(2);

        var job = result.jobs().get(0);
        assertThat(job.externalId()).isEqualTo("16718276");
        assertThat(job.title()).isEqualTo("Backend Engineer");
        assertThat(job.location()).isEqualTo("Berlin, DE");
        assertThat(job.applyUrl()).isEqualTo(baseUrl + "/companies/coolco/16718276-senior-ai-product-engineer");
        assertThat(job.postedDate()).isEqualTo(LocalDate.of(2024, 3, 15));
        assertThat(job.description()).isNull();
        assertThat(job.salaryMin()).isNull();
        assertThat(job.salaryMax()).isNull();
        assertThat(job.salaryCurrency()).isNull();
        assertThat(job.rawJson()).contains("\"id\":16718276");

        var second = result.jobs().get(1);
        assertThat(second.externalId()).isEqualTo("16718277");
        assertThat(second.postedDate()).isEqualTo(LocalDate.of(2024, 3, 10));
    }

    @Test
    void extract_cityOnly_locationIsCityOnly() {
        String items = """
                [
                  {
                    "id": 3,
                    "idParam": "3-designer",
                    "title": "Designer",
                    "city": {"cityName": "Hamburg"},
                    "createdAt": "2024-01-01T00:00:00Z"
                  }
                ]
                """;
        stubCompanyPage("co", pageJson(items, 1, 1));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("co")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).location()).isEqualTo("Hamburg");
    }

    @Test
    void extract_countryOnly_locationIsCountryCode() {
        String items = """
                [
                  {
                    "id": 4,
                    "idParam": "4-pm",
                    "title": "PM",
                    "city": {"cityName": ""},
                    "country": {"iso3166": "US"},
                    "createdAt": "2024-02-01T00:00:00Z"
                  }
                ]
                """;
        stubCompanyPage("co", pageJson(items, 1, 1));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("co")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).location()).isEqualTo("US");
    }

    @Test
    void extract_noLocation_locationIsNull() {
        String items = """
                [
                  {"id": 5, "idParam": "5-anywhere", "title": "Anywhere", "createdAt": "2024-02-01T00:00:00Z"}
                ]
                """;
        stubCompanyPage("co", pageJson(items, 1, 1));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("co")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).location()).isNull();
    }

    @Test
    void extract_multiplePages_aggregatesAndDeduplicates() {
        String pageOne = """
                [
                  {"id": 1, "idParam": "1-first", "title": "First",
                   "city": {"cityName": "Berlin"}, "country": {"iso3166": "DE"},
                   "createdAt": "2024-03-15T10:00:00Z"}
                ]
                """;
        // page 2 repeats job 1 and adds job 2
        String pageTwo = """
                [
                  {"id": 1, "idParam": "1-first", "title": "First",
                   "city": {"cityName": "Berlin"}, "country": {"iso3166": "DE"},
                   "createdAt": "2024-03-15T10:00:00Z"},
                  {"id": 2, "idParam": "2-second", "title": "Second",
                   "city": {"cityName": "Berlin"}, "country": {"iso3166": "DE"},
                   "createdAt": "2024-03-16T10:00:00Z"}
                ]
                """;

        stubFor(get(urlPathEqualTo("/companies/paged")).withQueryParam("page", absent())
                .willReturn(htmlResponse(htmlWithNextData(pageJson(pageOne, 1, 2)))));
        stubFor(get(urlPathEqualTo("/companies/paged")).withQueryParam("page", equalTo("2"))
                .willReturn(htmlResponse(htmlWithNextData(pageJson(pageTwo, 2, 2)))));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("paged")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);
        assertThat(result.jobs()).extracting(RawAggregatorJob::externalId).containsExactly("1", "2");

        verify(1, getRequestedFor(urlPathEqualTo("/companies/paged")).withQueryParam("page", absent()));
        verify(1, getRequestedFor(urlPathEqualTo("/companies/paged")).withQueryParam("page", equalTo("2")));
    }

    @Test
    void extract_pageCountAboveCap_stopsAt25Pages() {
        String items = """
                [
                  {"id": 1, "idParam": "1-first", "title": "First",
                   "city": {"cityName": "Berlin"}, "country": {"iso3166": "DE"}}
                ]
                """;
        // Every page claims 999 pages; the runaway guard must stop at 25 requests in total
        stubFor(get(urlPathEqualTo("/companies/capped"))
                .willReturn(htmlResponse(htmlWithNextData(pageJson(items, 1, 999)))));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("capped")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        verify(25, getRequestedFor(urlPathEqualTo("/companies/capped")));
    }

    @Test
    void extract_missingId_fallsBackToIdParam() {
        String items = """
                [
                  {
                    "idParam": "999-only-param",
                    "title": "No Numeric Id",
                    "city": {"cityName": "Berlin"},
                    "country": {"iso3166": "DE"}
                  }
                ]
                """;
        stubCompanyPage("co", pageJson(items, 1, 1));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("co")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).externalId()).isEqualTo("999-only-param");
        assertThat(result.jobs().get(0).applyUrl()).isEqualTo(baseUrl + "/companies/co/999-only-param");
    }

    @Test
    void extract_emptyItems_returnsEmpty() {
        stubCompanyPage("empty-co", pageJson("[]", 1, 1));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("empty-co")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_missingNextDataScript_returnsEmpty() {
        stubFor(get(urlPathMatching("/companies/.*"))
                .willReturn(htmlResponse("<html><body><div id=\"__next\">no payload</div></body></html>")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("no-payload")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_missingJobsNode_returnsEmpty() {
        stubCompanyPage("weird-co", "{\"props\":{\"pageProps\":{\"initialState\":{}}}}");

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("weird-co")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_404_returnsEmpty() {
        stubFor(get(urlPathMatching("/companies/.*"))
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
        stubFor(get(urlPathMatching("/companies/.*"))
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
    void extract_malformedNextData_returnsError() {
        stubCompanyPage("bad-json", "not valid json {");

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("bad-json")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
    }

    @Test
    void extract_nullCreatedAt_postedDateIsNull() {
        String items = """
                [
                  {
                    "id": 6,
                    "idParam": "6-role",
                    "title": "Role",
                    "city": {"cityName": "Berlin"},
                    "country": {"iso3166": "DE"}
                  }
                ]
                """;
        stubCompanyPage("co", pageJson(items, 1, 1));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.JOIN)
                .atsSlug("co")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).postedDate()).isNull();
    }

    private void stubCompanyPage(String slug, String nextDataJson) {
        stubFor(get(urlPathMatching("/companies/" + slug + ".*"))
                .willReturn(htmlResponse(htmlWithNextData(nextDataJson))));
    }

    private static ResponseDefinitionBuilder htmlResponse(String html) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/html; charset=utf-8")
                .withBody(html);
    }

    private static String htmlWithNextData(String nextDataJson) {
        return "<!doctype html><html><head><script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + nextDataJson
                + "</script></head><body><div id=\"__next\"></div></body></html>";
    }

    private static String pageJson(String itemsJson, int page, int pageCount) {
        return """
                {"props":{"pageProps":{"initialState":{"jobs":{"items":%s,"pagination":{"page":%d,"pageCount":%d,"pageSize":5,"perPage":5,"total":999},"isLoading":false,"filters":{},"aggregations":[]}}}},"page":"/companies/[slug]","query":{},"buildId":"test"}
                """.formatted(itemsJson, page, pageCount);
    }
}
