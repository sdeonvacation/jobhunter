package dev.jobhub.extraction;

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
class TeamtailorExtractorTest {

    private TeamtailorExtractor extractor;
    private String baseUrl;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        WebClient webClient = WebClient.builder().build();
        baseUrl = wmInfo.getHttpBaseUrl();
        extractor = new TeamtailorExtractor(webClient);
    }

    @Test
    void supportedTypes_returnsTeamtailor() {
        assertThat(extractor.supportedTypes()).containsExactly(AtsType.TEAMTAILOR);
    }

    @Test
    void canExtract_validEndpoint_returnsTrue() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url("https://career.company.com/jobs")
                .build();
        assertThat(extractor.canExtract(endpoint)).isTrue();
    }

    @Test
    void canExtract_nullUrl_returnsFalse() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url(null)
                .build();
        assertThat(extractor.canExtract(endpoint)).isFalse();
    }

    @Test
    void canExtract_blankUrl_returnsFalse() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url("  ")
                .build();
        assertThat(extractor.canExtract(endpoint)).isFalse();
    }

    @Test
    void canExtract_wrongAtsType_returnsFalse() {
        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.LEVER)
                .url("https://career.company.com/jobs")
                .build();
        assertThat(extractor.canExtract(endpoint)).isFalse();
    }

    @Test
    void extract_validRssFeed_returnsJobs() {
        String rss = buildRss("""
                <item>
                  <title>Senior Backend Engineer</title>
                  <description>&lt;p&gt;Build scalable systems.&lt;/p&gt;</description>
                  <pubDate>Mon, 15 Jan 2024 10:30:00 +0100</pubDate>
                  <link>%s/jobs/1234567-senior-backend-engineer</link>
                  <remoteStatus>hybrid</remoteStatus>
                  <guid>a1b2c3d4-e5f6-7890-abcd-ef1234567890</guid>
                  <tt:locations>
                    <tt:location>
                      <tt:name>Berlin</tt:name>
                      <tt:city>Berlin</tt:city>
                      <tt:country>Germany</tt:country>
                    </tt:location>
                  </tt:locations>
                  <tt:department>Engineering</tt:department>
                  <tt:role>Backend Engineer</tt:role>
                </item>
                """.formatted(baseUrl));

        stubFor(get(urlEqualTo("/jobs.rss"))
                .willReturn(okXml(rss)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url(baseUrl + "/jobs")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.totalFound()).isEqualTo(1);

        var job = result.jobs().get(0);
        assertThat(job.externalId()).isEqualTo("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        assertThat(job.title()).isEqualTo("Senior Backend Engineer");
        assertThat(job.location()).isEqualTo("Berlin, Germany (hybrid)");
        assertThat(job.description()).isEqualTo("Build scalable systems.");
        assertThat(job.applyUrl()).isEqualTo(baseUrl + "/jobs/1234567-senior-backend-engineer");
        assertThat(job.postedDate()).isEqualTo(LocalDate.of(2024, 1, 15));
    }

    @Test
    void extract_multipleJobs_returnsAll() {
        String rss = buildRss("""
                <item>
                  <title>Job A</title>
                  <description>&lt;p&gt;Desc A&lt;/p&gt;</description>
                  <pubDate>Tue, 02 Jan 2024 08:00:00 +0000</pubDate>
                  <link>%1$s/jobs/111-job-a</link>
                  <guid>guid-aaa</guid>
                  <tt:locations>
                    <tt:location>
                      <tt:city>Munich</tt:city>
                      <tt:country>Germany</tt:country>
                    </tt:location>
                  </tt:locations>
                  <tt:department>Sales</tt:department>
                </item>
                <item>
                  <title>Job B</title>
                  <description>&lt;p&gt;Desc B&lt;/p&gt;</description>
                  <pubDate>Wed, 03 Jan 2024 09:00:00 +0000</pubDate>
                  <link>%1$s/jobs/222-job-b</link>
                  <guid>guid-bbb</guid>
                  <remoteStatus>remote</remoteStatus>
                  <tt:locations>
                    <tt:location>
                      <tt:city>London</tt:city>
                      <tt:country>United Kingdom</tt:country>
                    </tt:location>
                  </tt:locations>
                  <tt:department>Engineering</tt:department>
                </item>
                """.formatted(baseUrl));

        stubFor(get(urlEqualTo("/jobs.rss"))
                .willReturn(okXml(rss)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url(baseUrl + "/jobs")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(2);

        var jobA = result.jobs().get(0);
        assertThat(jobA.title()).isEqualTo("Job A");
        assertThat(jobA.location()).isEqualTo("Munich, Germany");

        var jobB = result.jobs().get(1);
        assertThat(jobB.title()).isEqualTo("Job B");
        assertThat(jobB.location()).isEqualTo("London, United Kingdom (remote)");
    }

    @Test
    void extract_multipleLocations_joinsWithSemicolon() {
        String rss = buildRss("""
                <item>
                  <title>Multi-Location Role</title>
                  <description>&lt;p&gt;Work from multiple offices.&lt;/p&gt;</description>
                  <link>%s/jobs/333-multi-loc</link>
                  <guid>guid-multi</guid>
                  <tt:locations>
                    <tt:location>
                      <tt:city>Berlin</tt:city>
                      <tt:country>Germany</tt:country>
                    </tt:location>
                    <tt:location>
                      <tt:city>Munich</tt:city>
                      <tt:country>Germany</tt:country>
                    </tt:location>
                  </tt:locations>
                </item>
                """.formatted(baseUrl));

        stubFor(get(urlEqualTo("/jobs.rss"))
                .willReturn(okXml(rss)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url(baseUrl + "/jobs")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).location()).isEqualTo("Berlin, Germany; Munich, Germany");
    }

    @Test
    void extract_noLocations_remoteOnly() {
        String rss = buildRss("""
                <item>
                  <title>Fully Remote Dev</title>
                  <description>&lt;p&gt;Remote work.&lt;/p&gt;</description>
                  <link>%s/jobs/444-remote-dev</link>
                  <guid>guid-remote</guid>
                  <remoteStatus>remote</remoteStatus>
                </item>
                """.formatted(baseUrl));

        stubFor(get(urlEqualTo("/jobs.rss"))
                .willReturn(okXml(rss)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url(baseUrl + "/jobs")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).location()).isEqualTo("remote");
    }

    @Test
    void extract_noGuid_fallsBackToUrlId() {
        String rss = buildRss("""
                <item>
                  <title>No GUID Job</title>
                  <description>&lt;p&gt;Description.&lt;/p&gt;</description>
                  <link>%s/jobs/9876543-no-guid-job</link>
                  <tt:locations>
                    <tt:location>
                      <tt:city>Hamburg</tt:city>
                      <tt:country>Germany</tt:country>
                    </tt:location>
                  </tt:locations>
                </item>
                """.formatted(baseUrl));

        stubFor(get(urlEqualTo("/jobs.rss"))
                .willReturn(okXml(rss)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url(baseUrl + "/jobs")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).externalId()).isEqualTo("9876543");
    }

    @Test
    void extract_emptyFeed_returnsEmpty() {
        String rss = buildRss("");

        stubFor(get(urlEqualTo("/jobs.rss"))
                .willReturn(okXml(rss)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url(baseUrl + "/jobs")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_notFound404_returnsEmpty() {
        stubFor(get(urlEqualTo("/jobs.rss"))
                .willReturn(aResponse().withStatus(404)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url(baseUrl + "/jobs")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    void extract_rateLimited429_returnsRateLimited() {
        stubFor(get(urlEqualTo("/jobs.rss"))
                .willReturn(aResponse().withStatus(429)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url(baseUrl + "/jobs")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.RATE_LIMITED);
    }

    @Test
    void extract_forbidden403_returnsProtected() {
        stubFor(get(urlEqualTo("/jobs.rss"))
                .willReturn(aResponse().withStatus(403)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url(baseUrl + "/jobs")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.PROTECTED);
    }

    @Test
    void extract_serverError500_returnsError() {
        stubFor(get(urlEqualTo("/jobs.rss"))
                .willReturn(serverError()));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url(baseUrl + "/jobs")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).contains("500");
    }

    @Test
    void extract_truncatesLongTitle() {
        String longTitle = "A".repeat(600);
        String rss = buildRss("""
                <item>
                  <title>%s</title>
                  <description>&lt;p&gt;Desc&lt;/p&gt;</description>
                  <link>%s/jobs/555-long-title</link>
                  <guid>guid-long</guid>
                </item>
                """.formatted(longTitle, baseUrl));

        stubFor(get(urlEqualTo("/jobs.rss"))
                .willReturn(okXml(rss)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url(baseUrl + "/jobs")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).title()).hasSize(500);
    }

    @Test
    void extract_itemMissingLink_skipped() {
        String rss = buildRss("""
                <item>
                  <title>No Link Job</title>
                  <description>&lt;p&gt;Missing link.&lt;/p&gt;</description>
                  <guid>guid-nolink</guid>
                </item>
                <item>
                  <title>Valid Job</title>
                  <description>&lt;p&gt;Has link.&lt;/p&gt;</description>
                  <link>%s/jobs/666-valid</link>
                  <guid>guid-valid</guid>
                </item>
                """.formatted(baseUrl));

        stubFor(get(urlEqualTo("/jobs.rss"))
                .willReturn(okXml(rss)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url(baseUrl + "/jobs")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.jobs().get(0).title()).isEqualTo("Valid Job");
    }

    @Test
    void extract_htmlDescriptionStripped() {
        String rss = buildRss("""
                <item>
                  <title>HTML Desc Job</title>
                  <description>&lt;h3&gt;&lt;strong&gt;About the role&lt;/strong&gt;&lt;/h3&gt;&lt;p&gt;We are looking for &lt;em&gt;engineers&lt;/em&gt; &amp;amp; architects.&lt;/p&gt;</description>
                  <link>%s/jobs/777-html-desc</link>
                  <guid>guid-html</guid>
                </item>
                """.formatted(baseUrl));

        stubFor(get(urlEqualTo("/jobs.rss"))
                .willReturn(okXml(rss)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url(baseUrl + "/jobs")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        var job = result.jobs().get(0);
        assertThat(job.description()).doesNotContain("<h3>");
        assertThat(job.description()).doesNotContain("<strong>");
        assertThat(job.description()).contains("About the role");
        assertThat(job.description()).contains("engineers");
    }

    @Test
    void extract_locationFallsBackToName() {
        String rss = buildRss("""
                <item>
                  <title>Name Only Location</title>
                  <description>&lt;p&gt;Desc&lt;/p&gt;</description>
                  <link>%s/jobs/888-name-loc</link>
                  <guid>guid-name</guid>
                  <tt:locations>
                    <tt:location>
                      <tt:name>Frankfurt am Main</tt:name>
                    </tt:location>
                  </tt:locations>
                </item>
                """.formatted(baseUrl));

        stubFor(get(urlEqualTo("/jobs.rss"))
                .willReturn(okXml(rss)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url(baseUrl + "/jobs")
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs().get(0).location()).isEqualTo("Frankfurt am Main");
    }

    @Test
    void buildRssUrl_urlEndingWithJobs_appendsDotRss() {
        assertThat(extractor.buildRssUrl("https://career.company.com/jobs"))
                .isEqualTo("https://career.company.com/jobs.rss");
    }

    @Test
    void buildRssUrl_urlEndingWithTrailingSlash_appendsJobsRss() {
        assertThat(extractor.buildRssUrl("https://career.company.com/"))
                .isEqualTo("https://career.company.com/jobs.rss");
    }

    @Test
    void buildRssUrl_urlWithoutJobsPath_appendsJobsRss() {
        assertThat(extractor.buildRssUrl("https://career.company.com"))
                .isEqualTo("https://career.company.com/jobs.rss");
    }

    @Test
    void buildRssUrl_teamtailorSubdomain_appendsDotRss() {
        assertThat(extractor.buildRssUrl("https://company.teamtailor.com/jobs"))
                .isEqualTo("https://company.teamtailor.com/jobs.rss");
    }

    @Test
    void extract_urlWithoutJobsPath_requestsJobsRss() {
        String rss = buildRss("""
                <item>
                  <title>Test Job</title>
                  <description>&lt;p&gt;Desc&lt;/p&gt;</description>
                  <link>%s/jobs/999-test</link>
                  <guid>guid-test</guid>
                </item>
                """.formatted(baseUrl));

        stubFor(get(urlEqualTo("/jobs.rss"))
                .willReturn(okXml(rss)));

        var endpoint = CareerEndpoint.builder()
                .atsType(AtsType.TEAMTAILOR)
                .url(baseUrl)
                .build();

        var result = extractor.extract(endpoint);

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        verify(getRequestedFor(urlEqualTo("/jobs.rss")));
    }

    private String buildRss(String items) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <rss version="2.0" xmlns:tt="https://teamtailor.com/locations">
                  <channel>
                    <title>Test Company</title>
                    <description>Current job openings</description>
                    <link>%s/jobs</link>
                    %s
                  </channel>
                </rss>
                """.formatted(baseUrl, items);
    }
}
