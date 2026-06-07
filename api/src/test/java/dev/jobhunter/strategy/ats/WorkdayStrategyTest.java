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
class WorkdayStrategyTest {

    private WorkdayStrategy extractor;
    private String baseUrl;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        WebClient webClient = WebClient.builder().build();
        baseUrl = wmInfo.getHttpBaseUrl();
        // baseUrlTemplate without %s format specifiers routes all requests to WireMock
        extractor = new WorkdayStrategy(webClient, new ObjectMapper(), baseUrl);
    }

    @Test
    void supportedTypes_returnsWorkday() {
        assertThat(extractor.supports(AtsType.WORKDAY)).isTrue();
    }

    @Test
    void extract_singlePageResponse_returnsJobs() {
        String json = """
                {
                  "total": 2,
                  "jobPostings": [
                    {
                      "title": "Senior Backend Engineer",
                      "externalPath": "/en-US/job/Berlin/Senior-Backend-Engineer_123456",
                      "locationsText": "Berlin, Germany",
                      "postedOn": "Posted 3 Days Ago",
                      "bulletFields": ["Full time", "Regular", "Engineering"]
                    },
                    {
                      "title": "Product Manager",
                      "externalPath": "/en-US/job/Munich/Product-Manager_789012",
                      "locationsText": "Munich, Germany",
                      "postedOn": "Posted Today",
                      "bulletFields": ["Full time", "Product"]
                    }
                  ]
                }
                """;
        stubFor(post(urlPathMatching("/wday/cxs/.*"))
                .willReturn(okJson(json)));

        var endpoint = buildEndpoint("SAP", "1", "https://SAP.wd1.myworkdayjobs.com/en-US/SAPCareers");

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);
        assertThat(result.totalFound()).isEqualTo(2);

        var job1 = result.jobs().get(0);
        assertThat(job1.externalId()).isEqualTo("/en-US/job/Berlin/Senior-Backend-Engineer_123456");
        assertThat(job1.title()).isEqualTo("Senior Backend Engineer");
        assertThat(job1.location()).isEqualTo("Berlin, Germany");
        assertThat(job1.description()).isEqualTo("Full time | Regular | Engineering");
        assertThat(job1.applyUrl()).contains("/en-US/job/Berlin/Senior-Backend-Engineer_123456");

        var job2 = result.jobs().get(1);
        assertThat(job2.title()).isEqualTo("Product Manager");
        assertThat(job2.location()).isEqualTo("Munich, Germany");
    }

    @Test
    void extract_multiPageResponse_paginatesCorrectly() {
        // First page: 20 results, total=25
        String page1 = buildPageResponse(25, 20);
        // Second page: 5 results
        String page2 = buildPageResponse(25, 5);

        stubFor(post(urlPathMatching("/wday/cxs/.*"))
                .inScenario("pagination")
                .whenScenarioStateIs("Started")
                .willReturn(okJson(page1))
                .willSetStateTo("page2"));

        stubFor(post(urlPathMatching("/wday/cxs/.*"))
                .inScenario("pagination")
                .whenScenarioStateIs("page2")
                .willReturn(okJson(page2)));

        var endpoint = buildEndpoint("TestCo", "3", "https://TestCo.wd3.myworkdayjobs.com/Careers");

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(25);
        assertThat(result.totalFound()).isEqualTo(25);

        // Verify 2 POST requests were made
        verify(2, postRequestedFor(urlPathMatching("/wday/cxs/.*")));
    }

    @Test
    void extract_emptyResponse_returnsEmpty() {
        String json = """
                {
                  "total": 0,
                  "jobPostings": []
                }
                """;
        stubFor(post(urlPathMatching("/wday/cxs/.*"))
                .willReturn(okJson(json)));

        var endpoint = buildEndpoint("EmptyCo", "2", "https://EmptyCo.wd2.myworkdayjobs.com/Jobs");

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_protectedInstance403_returnsProtectedStatus() {
        stubFor(post(urlPathMatching("/wday/cxs/.*"))
                .willReturn(aResponse().withStatus(403).withBody("Forbidden")));

        var endpoint = buildEndpoint("SecureCo", "5", "https://SecureCo.wd5.myworkdayjobs.com/Internal");

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.PROTECTED);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_422OnSortBy_retriesWithoutSortBy() {
        // First request with sortBy returns 422, second without sortBy succeeds
        String successJson = """
                {
                  "total": 1,
                  "jobPostings": [
                    {
                      "title": "Engineer",
                      "externalPath": "/job/Engineer_001",
                      "locationsText": "Remote",
                      "postedOn": "Posted Yesterday",
                      "bulletFields": ["Full time"]
                    }
                  ]
                }
                """;

        stubFor(post(urlPathMatching("/wday/cxs/.*"))
                .withRequestBody(containing("sortBy"))
                .willReturn(aResponse().withStatus(422).withBody("Unprocessable Entity")));

        stubFor(post(urlPathMatching("/wday/cxs/.*"))
                .withRequestBody(notContaining("sortBy"))
                .willReturn(okJson(successJson)));

        var endpoint = buildEndpoint("QuirkyOrg", "1", "https://QuirkyOrg.wd1.myworkdayjobs.com/Careers");

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).title()).isEqualTo("Engineer");
    }

    @Test
    void extract_serverError500_returnsError() {
        stubFor(post(urlPathMatching("/wday/cxs/.*"))
                .willReturn(serverError()));

        var endpoint = buildEndpoint("BrokenCo", "1", "https://BrokenCo.wd1.myworkdayjobs.com/Jobs");

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("500");
    }

    @Test
    void extract_invalidUrl_noSite_returnsError() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.WORKDAY)
                .atsSlug("NoCo")
                .atsShardId("1")
                .url("")
                .build();

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("site");
    }

    @Test
    void extractSite_standardUrl_returnsLastPathSegment() {
        String site = extractor.extractSite("https://SAP.wd1.myworkdayjobs.com/en-US/SAPCareers");
        assertThat(site).isEqualTo("SAPCareers");
    }

    @Test
    void extractSite_trailingSlash_handledCorrectly() {
        String site = extractor.extractSite("https://SAP.wd1.myworkdayjobs.com/en-US/SAPCareers/");
        assertThat(site).isEqualTo("SAPCareers");
    }

    @Test
    void extractSite_nullUrl_returnsNull() {
        assertThat(extractor.extractSite(null)).isNull();
    }

    @Test
    void extractSite_blankUrl_returnsNull() {
        assertThat(extractor.extractSite("")).isNull();
    }

    @Test
    void extract_postedOnParsing_variousFormats() {
        String json = """
                {
                  "total": 3,
                  "jobPostings": [
                    {
                      "title": "Job A",
                      "externalPath": "/job/A_001",
                      "locationsText": "NYC",
                      "postedOn": "Posted 3 Days Ago",
                      "bulletFields": []
                    },
                    {
                      "title": "Job B",
                      "externalPath": "/job/B_002",
                      "locationsText": "LA",
                      "postedOn": "Posted 30+ Days Ago",
                      "bulletFields": []
                    },
                    {
                      "title": "Job C",
                      "externalPath": "/job/C_003",
                      "locationsText": "SF",
                      "postedOn": "",
                      "bulletFields": []
                    }
                  ]
                }
                """;
        stubFor(post(urlPathMatching("/wday/cxs/.*"))
                .willReturn(okJson(json)));

        var endpoint = buildEndpoint("DateCo", "1", "https://DateCo.wd1.myworkdayjobs.com/Jobs");

        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(3);

        // "Posted 3 Days Ago" should yield a date 3 days in the past
        assertThat(result.jobs().get(0).postedDate()).isNotNull();
        // "Posted 30+ Days Ago" should yield a date ~30 days in the past
        assertThat(result.jobs().get(1).postedDate()).isNotNull();
        // Empty postedOn should be null
        assertThat(result.jobs().get(2).postedDate()).isNull();
    }

    @Test
    void extract_requestBodyContainsExpectedFields() {
        stubFor(post(urlPathMatching("/wday/cxs/.*"))
                .willReturn(okJson("{\"total\":0,\"jobPostings\":[]}")));

        var endpoint = buildEndpoint("BodyCo", "2", "https://BodyCo.wd2.myworkdayjobs.com/Careers");

        extractor.fetch(FetchContext.forEndpoint(endpoint));

        verify(postRequestedFor(urlPathMatching("/wday/cxs/.*"))
                .withRequestBody(containing("\"appliedFacets\":{}"))
                .withRequestBody(containing("\"limit\":20"))
                .withRequestBody(containing("\"offset\":0"))
                .withRequestBody(containing("\"searchText\":\"\"")));
    }

    // --- Helper methods ---

    private CareerEndpoint buildEndpoint(String tenant, String shardId, String url) {
        return CareerEndpoint.builder()
                .atsType(AtsType.WORKDAY)
                .atsSlug(tenant)
                .atsShardId(shardId)
                .url(url)
                .build();
    }

    private String buildPageResponse(int total, int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"total\":").append(total).append(",\"jobPostings\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("""
                    {"title":"Job %d","externalPath":"/job/Job_%d","locationsText":"Location %d","postedOn":"Posted %d Days Ago","bulletFields":["Full time"]}""",
                    i, i, i, i + 1));
        }
        sb.append("]}");
        return sb.toString();
    }
}
