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

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest
class AshbyExtractorTest {

    private AshbyExtractor extractor;
    private String baseUrl;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        WebClient webClient = WebClient.builder().build();
        baseUrl = wmInfo.getHttpBaseUrl();
        extractor = new AshbyExtractor(webClient, new ObjectMapper(), baseUrl);
    }

    @Test
    void supportedTypes_returnsAshby() {
        assertThat(extractor.supportedTypes()).containsExactly(AtsType.ASHBY);
    }

    @Test
    void canExtract_validEndpoint_returnsTrue() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.ASHBY)
                .atsSlug("coolstartup")
                .build();
        assertThat(extractor.canExtract(endpoint)).isTrue();
    }

    @Test
    void canExtract_nullSlug_returnsFalse() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.ASHBY)
                .atsSlug(null)
                .build();
        assertThat(extractor.canExtract(endpoint)).isFalse();
    }

    @Test
    void canExtract_blankSlug_returnsFalse() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.ASHBY)
                .atsSlug("  ")
                .build();
        assertThat(extractor.canExtract(endpoint)).isFalse();
    }

    @Test
    void canExtract_wrongAtsType_returnsFalse() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.LEVER)
                .atsSlug("slug")
                .build();
        assertThat(extractor.canExtract(endpoint)).isFalse();
    }

    @Test
    void extract_validResponseWithCompensation_returnsJobsWithSalary() {
        String json = """
                {
                  "jobs": [
                    {
                      "id": "job-001",
                      "title": "Senior Engineer",
                      "location": "San Francisco, CA",
                      "descriptionPlain": "Build distributed systems at scale.",
                      "applyUrl": "https://jobs.ashbyhq.com/coolco/job-001",
                      "publishedDate": "2024-01-15",
                      "compensation": {
                        "currency": "USD",
                        "compensationTierSummary": "$150,000 - $200,000"
                      }
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/posting-api/job-board/.*"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.ASHBY)
                .atsSlug("coolco")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.totalFound()).isEqualTo(1);

        var job = result.jobs().get(0);
        assertThat(job.externalId()).isEqualTo("job-001");
        assertThat(job.title()).isEqualTo("Senior Engineer");
        assertThat(job.location()).isEqualTo("San Francisco, CA");
        assertThat(job.description()).isEqualTo("Build distributed systems at scale.");
        assertThat(job.applyUrl()).isEqualTo("https://jobs.ashbyhq.com/coolco/job-001");
        assertThat(job.postedDate()).isEqualTo(LocalDate.of(2024, 1, 15));
        assertThat(job.salaryMin()).isEqualByComparingTo(new BigDecimal("150000"));
        assertThat(job.salaryMax()).isEqualByComparingTo(new BigDecimal("200000"));
        assertThat(job.salaryCurrency()).isEqualTo("USD");
    }

    @Test
    void extract_validResponseWithoutCompensation_returnsJobsNullSalary() {
        String json = """
                {
                  "jobs": [
                    {
                      "id": "job-002",
                      "title": "Product Manager",
                      "location": "Remote",
                      "descriptionPlain": "Lead product strategy.",
                      "applyUrl": "https://jobs.ashbyhq.com/co/job-002",
                      "publishedDate": "2024-02-01",
                      "compensation": null
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/posting-api/job-board/.*"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.ASHBY)
                .atsSlug("nopayco")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);

        var job = result.jobs().get(0);
        assertThat(job.externalId()).isEqualTo("job-002");
        assertThat(job.title()).isEqualTo("Product Manager");
        assertThat(job.salaryMin()).isNull();
        assertThat(job.salaryMax()).isNull();
        assertThat(job.salaryCurrency()).isNull();
    }

    @Test
    void extract_multipleJobs_returnsAll() {
        String json = """
                {
                  "jobs": [
                    {
                      "id": "a1",
                      "title": "Job A",
                      "location": "Berlin",
                      "descriptionPlain": "Desc A",
                      "applyUrl": "https://jobs.ashbyhq.com/co/a1",
                      "publishedDate": "2024-01-01"
                    },
                    {
                      "id": "b2",
                      "title": "Job B",
                      "location": "London",
                      "descriptionPlain": "Desc B",
                      "applyUrl": "https://jobs.ashbyhq.com/co/b2",
                      "publishedDate": "2024-01-02",
                      "compensation": {
                        "currency": "GBP",
                        "compensationTierSummary": "£80,000 - £120,000"
                      }
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/posting-api/job-board/.*"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.ASHBY)
                .atsSlug("multi")
                .build();

        var result = extractor.extract(endpoint);
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);
        assertThat(result.totalFound()).isEqualTo(2);

        var jobB = result.jobs().get(1);
        assertThat(jobB.salaryMin()).isEqualByComparingTo(new BigDecimal("80000"));
        assertThat(jobB.salaryMax()).isEqualByComparingTo(new BigDecimal("120000"));
        assertThat(jobB.salaryCurrency()).isEqualTo("GBP");
    }

    @Test
    void extract_emptyJobBoard_returnsEmpty() {
        stubFor(get(urlPathMatching("/posting-api/job-board/.*"))
                .willReturn(okJson("{\"jobs\": []}")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.ASHBY)
                .atsSlug("emptyco")
                .build();

        var result = extractor.extract(endpoint);
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_notFound404_returnsEmpty() {
        stubFor(get(urlPathMatching("/posting-api/job-board/.*"))
                .willReturn(aResponse().withStatus(404)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.ASHBY)
                .atsSlug("nosuchboard")
                .build();

        var result = extractor.extract(endpoint);
        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_serverError500_returnsError() {
        stubFor(get(urlPathMatching("/posting-api/job-board/.*"))
                .willReturn(serverError()));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.ASHBY)
                .atsSlug("brokenco")
                .build();

        var result = extractor.extract(endpoint);
        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("500");
    }

    @Test
    void extract_compensationWithoutRange_returnNullSalary() {
        String json = """
                {
                  "jobs": [
                    {
                      "id": "job-x",
                      "title": "Designer",
                      "location": "NYC",
                      "descriptionPlain": "Design things.",
                      "applyUrl": "https://jobs.ashbyhq.com/co/job-x",
                      "publishedDate": "2024-03-01",
                      "compensation": {
                        "currency": "USD",
                        "compensationTierSummary": "Competitive"
                      }
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/posting-api/job-board/.*"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.ASHBY)
                .atsSlug("compco")
                .build();

        var result = extractor.extract(endpoint);
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);

        var job = result.jobs().get(0);
        assertThat(job.salaryCurrency()).isEqualTo("USD");
        assertThat(job.salaryMin()).isNull();
        assertThat(job.salaryMax()).isNull();
    }

    @Test
    void extract_includeCompensationQueryParam_isSent() {
        stubFor(get(urlPathMatching("/posting-api/job-board/.*"))
                .withQueryParam("includeCompensation", equalTo("true"))
                .willReturn(okJson("{\"jobs\": []}")));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.ASHBY)
                .atsSlug("paramcheck")
                .build();

        extractor.extract(endpoint);

        verify(getRequestedFor(urlPathMatching("/posting-api/job-board/.*"))
                .withQueryParam("includeCompensation", equalTo("true")));
    }

    @Test
    void extract_htmlFallback_stripsTagsWhenPlainEmpty() {
        String json = """
                {
                  "jobs": [
                    {
                      "id": "html-job",
                      "title": "Engineer",
                      "location": "Remote",
                      "descriptionPlain": "",
                      "descriptionHtml": "<p>We are looking for a <strong>great</strong> engineer.</p>",
                      "applyUrl": "https://jobs.ashbyhq.com/co/html-job",
                      "publishedDate": "2024-05-01"
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/posting-api/job-board/.*"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.ASHBY)
                .atsSlug("htmlco")
                .build();

        var result = extractor.extract(endpoint);
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).description()).isEqualTo("We are looking for a great engineer.");
    }

    @Test
    void extract_titleTruncation_truncatesLongTitle() {
        String longTitle = "A".repeat(600);
        String json = """
                {
                  "jobs": [
                    {
                      "id": "trunc-job",
                      "title": "%s",
                      "location": "Anywhere",
                      "descriptionPlain": "Short desc.",
                      "applyUrl": "https://jobs.ashbyhq.com/co/trunc-job",
                      "publishedDate": "2024-04-01"
                    }
                  ]
                }
                """.formatted(longTitle);
        stubFor(get(urlPathMatching("/posting-api/job-board/.*"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.ASHBY)
                .atsSlug("truncco")
                .build();

        var result = extractor.extract(endpoint);
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).title()).hasSize(500);
    }

    @Test
    void extract_zonedDateTimeFallback_parsesCorrectly() {
        String json = """
                {
                  "jobs": [
                    {
                      "id": "zdt-job",
                      "title": "Dev",
                      "location": "Berlin",
                      "descriptionPlain": "Code.",
                      "applyUrl": "https://jobs.ashbyhq.com/co/zdt-job",
                      "publishedDate": "2024-06-15T12:00:00Z"
                    }
                  ]
                }
                """;
        stubFor(get(urlPathMatching("/posting-api/job-board/.*"))
                .willReturn(okJson(json)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.ASHBY)
                .atsSlug("zdtco")
                .build();

        var result = extractor.extract(endpoint);
        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).postedDate()).isEqualTo(LocalDate.of(2024, 6, 15));
    }
}
