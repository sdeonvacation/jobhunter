package dev.jobhunter.strategy.ats;

import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class SuccessFactorsStrategyTest {

    private WebClient webClient;
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;
    private WebClient.RequestHeadersSpec requestHeadersSpec;
    private WebClient.ResponseSpec responseSpec;
    private SuccessFactorsStrategy extractor;

    @BeforeEach
    void setUp() {
        webClient = mock(WebClient.class);
        requestHeadersUriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
        responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);

        extractor = new SuccessFactorsStrategy(webClient);
    }

    private static final String CLASSIC_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Job-Listing>
            <Job>
            <JobTitle><![CDATA[Assistant Store Manager - CDI 35h -Velizy (78)]]></JobTitle>
            <Job-Description><![CDATA[<div><p>The future looks like you</p></div>]]></Job-Description>
            <ReqId>66755</ReqId>
            <label>City</label>
            <value></value>
            </Job>
            <Job>
            <JobTitle><![CDATA[(Senior) Backend Engineer (m/w/d)]]></JobTitle>
            <Job-Description><![CDATA[<div><p>We are based in Germany</p></div>]]></Job-Description>
            <ReqId>66756</ReqId>
            </Job>
            </Job-Listing>
            """;

    @Test
    void supportedTypes_containsSuccessFactors() {
        assertTrue(extractor.supportedTypes().contains(AtsType.SUCCESSFACTORS));
    }

    @Test
    void supportedTypes_doesNotContainOtherType() {
        assertFalse(extractor.supportedTypes().contains(AtsType.GREENHOUSE));
    }

    @Test
    void fetch_returnError_whenUrlBlank() {
        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("   ")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();
        var result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
    }

    @Test
    void parseTotalCount_extractsFromResultsText() {
        String html = "<div>Results 1 – 25 of 303</div>";
        assertEquals(303, extractor.parseTotalCount(html));
    }

    @Test
    void parseTotalCount_handlesHyphenVariant() {
        String html = "<span>Results 1 - 25 of 150</span>";
        assertEquals(150, extractor.parseTotalCount(html));
    }

    @Test
    void parseTotalCount_returnsZeroWhenNoMatch() {
        String html = "<div>No results found</div>";
        assertEquals(0, extractor.parseTotalCount(html));
    }

    @Test
    void parseTotalCount_handlesLargeNumbers() {
        String html = "<div>Results 26 – 50 of 1234</div>";
        assertEquals(1234, extractor.parseTotalCount(html));
    }

    @Test
    void parseListings_extractsJobsFromTable() {
        String html = """
                <html><body>
                <table>
                  <tr><td><a href="/job/Berlin-Software-Developer-10557/1391851433/">Software Developer</a></td></tr>
                  <tr><td><a href="/job/Munich-Data-Engineer-80331/9876543210/">Data Engineer</a></td></tr>
                </table>
                </body></html>
                """;

        List<SuccessFactorsStrategy.JobListing> listings = extractor.parseListings(html, "https://jobs.sap.com");

        assertEquals(2, listings.size());

        assertEquals("1391851433", listings.get(0).externalId());
        assertEquals("Software Developer", listings.get(0).title());
        assertEquals("Berlin", listings.get(0).location());
        assertEquals("https://jobs.sap.com/job/Berlin-Software-Developer-10557/1391851433/", listings.get(0).url());

        assertEquals("9876543210", listings.get(1).externalId());
        assertEquals("Data Engineer", listings.get(1).title());
        assertEquals("Munich", listings.get(1).location());
    }

    @Test
    void parseListings_skipsLinksWithoutNumericId() {
        String html = """
                <html><body>
                <a href="/job/Berlin-Developer/abc/">Developer</a>
                <a href="/job/Berlin-Developer-10557/1234567/">Valid Job</a>
                </body></html>
                """;

        List<SuccessFactorsStrategy.JobListing> listings = extractor.parseListings(html, "https://jobs.example.com");
        assertEquals(1, listings.size());
        assertEquals("1234567", listings.get(0).externalId());
    }

    @Test
    void parseListings_handlesAbsoluteUrls() {
        String html = """
                <html><body>
                <a href="https://jobs.sap.com/job/Berlin-Dev-10557/999/">Dev</a>
                </body></html>
                """;

        List<SuccessFactorsStrategy.JobListing> listings = extractor.parseListings(html, "https://jobs.sap.com");
        assertEquals(1, listings.size());
        assertEquals("https://jobs.sap.com/job/Berlin-Dev-10557/999/", listings.get(0).url());
    }

    @Test
    void parseDescription_extractsFromJobDescription() {
        String html = """
                <html><body>
                <div class="jobdescription">
                  <p>We are looking for a talented developer.</p>
                  <ul><li>Java experience</li><li>Spring Boot</li></ul>
                </div>
                </body></html>
                """;

        String description = extractor.parseDescription(html);
        assertNotNull(description);
        assertTrue(description.contains("talented developer"));
        assertTrue(description.contains("Java experience"));
    }

    @Test
    void parseDescription_fallsBackToMain() {
        String html = """
                <html><body>
                <main>
                  <p>Job description content here.</p>
                </main>
                </body></html>
                """;

        String description = extractor.parseDescription(html);
        assertNotNull(description);
        assertTrue(description.contains("Job description content here"));
    }

    @Test
    void parseDescription_returnsNullWhenNoContent() {
        String html = "<html><body></body></html>";
        assertNull(extractor.parseDescription(html));
    }

    @Test
    void extractExternalId_extractsNumericId() {
        assertEquals("1391851433", extractor.extractExternalId("/job/Berlin-Dev-10557/1391851433/"));
        assertEquals("999", extractor.extractExternalId("/job/Munich-Engineer-80331/999/"));
    }

    @Test
    void extractExternalId_handlesNoTrailingSlash() {
        assertEquals("12345", extractor.extractExternalId("/job/Berlin-Dev-10557/12345"));
    }

    @Test
    void extractExternalId_returnsNullForNonNumeric() {
        assertNull(extractor.extractExternalId("/job/Berlin-Dev-10557/abc/"));
    }

    @Test
    void extract_returnsEmptyWhenNoJobs() {
        String html = "<html><body><div>Results 0 – 0 of 0</div></body></html>";
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(html));

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://jobs.example.com")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertEquals(0, result.totalFound());
    }

    @Test
    void extract_returnsEmptyOnNullResponse() {
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.empty());

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://jobs.example.com")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertEquals(0, result.totalFound());
    }

    @Test
    void extract_returnsProtectedOnForbidden() {
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.error(WebClientResponseException.create(403, "Forbidden", null, null, null)));

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://jobs.example.com")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertNotNull(result);
        assertEquals("Protected endpoint - requires authentication", result.errorMessage());
    }

    @Test
    void extract_returnsErrorOnException() {
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.error(new RuntimeException("Connection timeout")));

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://jobs.example.com")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("Connection timeout"));
    }

    @Test
    void extract_singlePageExtraction() {
        String searchHtml = """
                <html><body>
                <div>Results 1 – 2 of 2</div>
                <table>
                  <tr><td><a href="/job/Berlin-Dev-10557/111/">Developer</a></td></tr>
                  <tr><td><a href="/job/Munich-Eng-80331/222/">Engineer</a></td></tr>
                </table>
                </body></html>
                """;

        String detailHtml = """
                <html><body>
                <div class="jobdescription"><p>Great job opportunity</p></div>
                </body></html>
                """;

        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.just(searchHtml))   // search page
                .thenReturn(Mono.just(detailHtml))   // detail page 1
                .thenReturn(Mono.just(detailHtml));  // detail page 2

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://jobs.example.com")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertEquals(2, result.totalFound());
        assertEquals(2, result.jobs().size());

        RawAggregatorJob first = result.jobs().get(0);
        assertEquals("111", first.externalId());
        assertEquals("Developer", first.title());
        assertEquals("Berlin", first.location());
        assertEquals("https://jobs.example.com/job/Berlin-Dev-10557/111/", first.applyUrl());
        assertTrue(first.description().contains("Great job opportunity"));
    }

    @Test
    void extract_handlesDetailPageFailureGracefully() {
        String searchHtml = """
                <html><body>
                <div>Results 1 – 1 of 1</div>
                <a href="/job/Berlin-Dev-10557/111/">Developer</a>
                </body></html>
                """;

        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.just(searchHtml))
                .thenReturn(Mono.error(new RuntimeException("timeout")));

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://jobs.example.com")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertEquals(1, result.totalFound());
        assertEquals("111", result.jobs().get(0).externalId());
        assertNull(result.jobs().get(0).description()); // graceful fallback
    }

    @Test
    void extract_normalizesTrailingSlashInUrl() {
        String searchHtml = """
                <html><body>
                <div>Results 1 – 1 of 1</div>
                <a href="/job/Berlin-Dev-10557/111/">Dev</a>
                </body></html>
                """;
        String detailHtml = "<html><body><main><p>Desc</p></main></body></html>";

        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.just(searchHtml))
                .thenReturn(Mono.just(detailHtml));

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://jobs.example.com/")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertEquals(1, result.totalFound());
        // URL should not have double slashes
        assertTrue(result.jobs().get(0).applyUrl().startsWith("https://jobs.example.com/job/"));
    }

    // --- Classic SuccessFactors board support ---

    @Test
    void isClassicBoard_returnsTrueForCareer5SuccessfactorsEu() {
        assertTrue(extractor.isClassicBoard("https://career5.successfactors.eu/careers?company=CAProduction"));
    }

    @Test
    void isClassicBoard_returnsTrueForCompanyQueryParam() {
        assertTrue(extractor.isClassicBoard("https://jobs.example.com/careers?company=ACME"));
    }

    @Test
    void isClassicBoard_returnsFalseForJobs2webStyle() {
        assertFalse(extractor.isClassicBoard("https://jobs.voith.com/search/?locale=en_US"));
        assertFalse(extractor.isClassicBoard("https://jobs.adidas-group.com/search/"));
        assertFalse(extractor.isClassicBoard(null));
        assertFalse(extractor.isClassicBoard("   "));
    }

    @Test
    void extractCompany_parsesQueryParam() {
        assertEquals("CAProduction", extractor.extractCompany("https://career5.successfactors.eu/careers?company=CAProduction"));
        assertEquals("ACME", extractor.extractCompany("https://career5.successfactors.eu/careers?locale=en&company=ACME"));
        assertNull(extractor.extractCompany("https://career5.successfactors.eu/careers"));
        assertNull(extractor.extractCompany("https://jobs.example.com/search/?locale=en_US"));
    }

    @Test
    void extractOrigin_returnsSchemeAndHost() {
        assertEquals("https://career5.successfactors.eu",
                extractor.extractOrigin("https://career5.successfactors.eu/careers?company=CAProduction"));
        assertEquals("https://jobs.voith.com", extractor.extractOrigin("https://jobs.voith.com/search/?locale=en_US"));
        assertNull(extractor.extractOrigin(null));
    }

    @Test
    void parseClassicXml_extractsJobs() {
        List<RawAggregatorJob> jobs = extractor.parseClassicXml(CLASSIC_XML, "https://career5.successfactors.eu", "CAProduction");

        assertEquals(2, jobs.size());

        RawAggregatorJob first = jobs.get(0);
        assertEquals("66755", first.externalId());
        assertEquals("Assistant Store Manager - CDI 35h -Velizy (78)", first.title());
        assertEquals("The future looks like you", first.description());
        assertFalse(first.description().contains("<p>"));
        assertEquals("Velizy", first.location());
        assertEquals("https://career5.successfactors.eu/careers?company=CAProduction&career_job_req_id=66755&career_ns=job_application&lang=en_GB",
                first.applyUrl());

        RawAggregatorJob second = jobs.get(1);
        assertEquals("66756", second.externalId());
        assertEquals("(Senior) Backend Engineer (m/w/d)", second.title());
        assertEquals("Germany", second.location());
    }

    @Test
    void parseClassicXml_skipsJobsWithoutReqId() {
        String xml = """
                <Job-Listing>
                <Job>
                <JobTitle><![CDATA[No ReqId Job]]></JobTitle>
                <Job-Description><![CDATA[<p>desc</p>]]></Job-Description>
                </Job>
                <Job>
                <JobTitle><![CDATA[Valid Job]]></JobTitle>
                <Job-Description><![CDATA[<p>desc</p>]]></Job-Description>
                <ReqId>12345</ReqId>
                </Job>
                </Job-Listing>
                """;

        List<RawAggregatorJob> jobs = extractor.parseClassicXml(xml, "https://career5.successfactors.eu", "CAProduction");
        assertEquals(1, jobs.size());
        assertEquals("12345", jobs.get(0).externalId());
    }

    @Test
    void extractLocation_fromTitleAfterHyphen() {
        String location = extractor.extractLocation("Assistant Store Manager - CDI 35h -Velizy (78)", null);
        assertTrue(location.contains("Velizy"));
    }

    @Test
    void extractLocation_fromTitleEnd() {
        assertEquals("Albacete", extractor.extractLocation("Store Lead Albacete", null));
    }

    @Test
    void extractLocation_fromDescriptionCountry() {
        assertEquals("Germany", extractor.extractLocation("Backend Engineer", "based in Germany"));
    }

    @Test
    void extractLocation_returnsNullWhenNoHints() {
        assertNull(extractor.extractLocation("(Senior) Backend Engineer (m/w/d)", "The future looks like you and requires excellent communication skills."));
    }

    @Test
    void extractLocation_ignoresHyphenSeparatedFunctionWord() {
        // Many boards use the hyphen as a seniority/function separator, not a location marker.
        assertNull(extractor.extractLocation("AVP-Ratings (Covered Bonds)", null));
        assertNull(extractor.extractLocation("Asst Dir-Product Manager", null));
        assertNull(extractor.extractLocation("Associate Director - Relationship Manager - Banking", null));
    }

    @Test
    void extractLocation_doesNotMatchCityInsideWord() {
        // "gent" inside "agents"/"urgent" must not resolve to Ghent.
        assertNull(extractor.extractLocation("Backend Engineer", "Our agents handle urgent escalations."));
        assertEquals("Germany", extractor.extractLocation("Backend Engineer", "based in Germany"));
    }

    @Test
    void fetch_usesClassicPath_whenClassicBoard() {
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(CLASSIC_XML));

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://career5.successfactors.eu/careers?company=CAProduction")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertEquals(ExtractionStatus.SUCCESS, result.status());
        assertEquals(2, result.totalFound());
        assertTrue(result.jobs().get(0).applyUrl().contains("career_job_req_id=66755"));
        verify(requestHeadersUriSpec).uri(argThat((String url) -> url != null
                && url.contains("/career?company=CAProduction&&career_ns=job_listing_summary&&resultType=XML")));
    }

    @Test
    void fetch_classicPath_returnsEmpty_whenBlankResponse() {
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(""));

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://career5.successfactors.eu/careers?company=CAProduction")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertEquals(ExtractionStatus.EMPTY, result.status());
    }

    @Test
    void fetch_classicPath_returnsProtected_on403() {
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.error(WebClientResponseException.create(403, "Forbidden", null, null, null)));

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://career5.successfactors.eu/careers?company=CAProduction")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertEquals(ExtractionStatus.PROTECTED, result.status());
        assertEquals("Protected endpoint - requires authentication", result.errorMessage());
    }

    @Test
    void fetch_classicPath_returnsError_whenMissingCompany() {
        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://career5.successfactors.eu/careers")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));
        assertEquals(ExtractionStatus.ERROR, result.status());
        assertEquals("classic SF: missing company param", result.errorMessage());
    }

    // --- Fraunhofer CSB (Career Site Builder) support ---

    private String loadFixture(String name) {
        try (var in = getClass().getResourceAsStream("/fixtures/successfactors/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read fixture: " + name, e);
        }
    }

    private String buildSearchPage(List<String[]> rows, String paginationLabel) {
        StringBuilder sb = new StringBuilder("<html><body><div>").append(paginationLabel).append("</div><table>");
        for (String[] row : rows) {
            sb.append("<tr class=\"data-row\"><td><a href=\"").append(row[0]).append("\">")
                    .append(row[1]).append("</a></td>");
            if (row.length > 2 && row[2] != null && !row[2].isBlank()) {
                sb.append("<td>").append(row[2]).append("</td>");
            }
            sb.append("</tr>");
        }
        return sb.append("</table></body></html>").toString();
    }

    private static final String MICRODATA_DETAIL = """
            <html><body>
            <div class="jobdescription"><p>Detail description for the job posting.</p></div>
            <meta itemprop="datePosted" content="Mon Aug 31 02:00:00 UTC 2026">
            <meta itemprop="streetAddress" content="Darmstadt, DE, 64295">
            </body></html>
            """;

    @Test
    void parseTotalCount_fraunhoferFixture() {
        String html = loadFixture("fraunhofer-search.html");
        assertEquals(162, extractor.parseTotalCount(html));
    }

    @Test
    void parseListings_fraunhoferFixture() {
        String html = loadFixture("fraunhofer-search.html");
        List<SuccessFactorsStrategy.JobListing> listings =
                extractor.parseListings(html, "https://jobs.fraunhofer.de");

        assertEquals(25, listings.size());
        for (SuccessFactorsStrategy.JobListing listing : listings) {
            assertTrue(listing.externalId().matches("\\d+"), "externalId must be numeric: " + listing.externalId());
            assertTrue(listing.url().startsWith("https://jobs.fraunhofer.de/job/"),
                    "url must be absolute: " + listing.url());
        }
        // First listing carries a city in the second table cell (colShifttype)
        assertThat(listings.get(0).location()).isNotBlank();
    }

    @Test
    void parseDescription_fraunhoferDetailFixture() {
        String html = loadFixture("fraunhofer-detail.html");
        String description = extractor.parseDescription(html);

        assertNotNull(description);
        assertFalse(description.isBlank());
        assertTrue(description.length() > 200);
        assertTrue(description.contains("expanding its new location in Heilbronn"));
        // Full JD must be captured, not just the .jobdescription first section:
        // the skills section (Java, C/C++, Python) lives outside .jobdescription
        // but inside span[itemprop=description].
        assertTrue(description.contains("Java, C/C++, Python"));
        assertTrue(description.contains("compiler construction"));
        assertTrue(description.contains("What we offer"));
    }

    @Test
    void buildSearchUrl_withQueryParams() {
        String url = extractor.buildSearchUrl("https://jobs.fraunhofer.de/search/?q=Software&locale=en_US", 25);
        assertEquals("https://jobs.fraunhofer.de/search/?q=Software&locationsearch=Germany&locale=en_US&startrow=25", url);
    }

    @Test
    void buildSearchUrl_bareOriginDefaults() {
        String url = extractor.buildSearchUrl("https://jobs.fraunhofer.de", 0);
        assertEquals("https://jobs.fraunhofer.de/search/?q=&locationsearch=Germany&locale=en_US&startrow=0", url);
    }

    @Test
    void parsePostedDate_fraunhoferFixture() {
        String html = loadFixture("fraunhofer-detail.html");
        assertEquals(LocalDate.of(2026, 8, 31), extractor.parsePostedDate(html));
    }

    @Test
    void parsePostedDate_malformed_returnsNull() {
        String html = "<html><body><meta itemprop=\"datePosted\" content=\"not-a-date\"></body></html>";
        assertNull(extractor.parsePostedDate(html));
    }

    @Test
    void parsePostedDate_absent_returnsNull() {
        String html = "<html><body><p>No microdata here</p></body></html>";
        assertNull(extractor.parsePostedDate(html));
    }

    @Test
    void parseStreetAddress_fraunhoferFixture() {
        String html = loadFixture("fraunhofer-detail.html");
        assertEquals("Darmstadt, DE, 64295", extractor.parseStreetAddress(html));
    }

    @Test
    void parseStreetAddress_absent_returnsNull() {
        String html = "<html><body><p>No microdata here</p></body></html>";
        assertNull(extractor.parseStreetAddress(html));
    }

    @Test
    void fetch_qSoftwareEndpoint_paginatesAndPopulatesMicrodata() {
        // Two pages: 25 + 2 = 27 listings -> 2 search pages + 27 detail pages
        List<String[]> page1Rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            page1Rows.add(new String[]{"/job/Berlin-Dev-10557/" + (1000 + i) + "/", "Job " + i, "Berlin"});
        }
        List<String[]> page2Rows = List.of(
                new String[]{"/job/Munich-Eng-80331/2001/", "Job 26", "Munich"},
                new String[]{"/job/Hamburg-Dev-20095/2002/", "Job 27", "Hamburg"}
        );
        String page1 = buildSearchPage(page1Rows, "Results 1 – 25 of 27");
        String page2 = buildSearchPage(page2Rows, "Results 26 – 27 of 27");

        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.just(page1))   // search page 1
                .thenReturn(Mono.just(page2))   // search page 2
                .thenReturn(Mono.just(MICRODATA_DETAIL)); // detail pages (reused for all 27)

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://jobs.fraunhofer.de/search/?q=Software&locale=en_US")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertEquals(ExtractionStatus.SUCCESS, result.status());
        assertEquals(27, result.totalFound());
        assertEquals(27, result.jobs().size());
        assertTrue(result.jobs().stream().anyMatch(j -> LocalDate.of(2026, 8, 31).equals(j.postedDate())));
        assertTrue(result.jobs().get(0).applyUrl().startsWith("https://jobs.fraunhofer.de/job/"));
    }

    @Test
    void fetch_locationFallsBackToStreetAddress() {
        // Listing row has no second td and its URL carries a blank city segment (" "), so
        // parseListings yields a blank location; the assembled job location must fall back
        // to the detail page's streetAddress microdata.
        String searchHtml = buildSearchPage(
                List.<String[]>of(new String[]{"/job/ /12345/", "Job"}),
                "Results 1 – 1 of 1");

        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.just(searchHtml))
                .thenReturn(Mono.just(MICRODATA_DETAIL));

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://jobs.fraunhofer.de")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertEquals(ExtractionStatus.SUCCESS, result.status());
        assertEquals(1, result.totalFound());
        assertEquals("Darmstadt, DE, 64295", result.jobs().get(0).location());
    }

    @Test
    void fetch_bareOriginEndpoint_keepsLegacyBehavior() {
        String searchHtml = buildSearchPage(
                List.<String[]>of(new String[]{"/job/Berlin-Dev-10557/111/", "Developer", "Berlin"}),
                "Results 1 – 1 of 1");
        String detailHtml = "<html><body><div class=\"jobdescription\"><p>Legacy detail</p></div></body></html>";

        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.just(searchHtml))
                .thenReturn(Mono.just(detailHtml));

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://jobs.fraunhofer.de")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertEquals(ExtractionStatus.SUCCESS, result.status());
        assertEquals(1, result.totalFound());
        assertEquals("Berlin", result.jobs().get(0).location());
        assertTrue(result.jobs().get(0).applyUrl().startsWith("https://jobs.fraunhofer.de/job/"));
    }

    // --- Next.js site support (jobs.sap.com migrated to a Vercel-hosted Next SPA) ---

    private static String nextShell(String countText) {
        return "<html><head><title>Jobs | SAP Careers</title></head>"
                + "<body><div id=\"__next\">SPA shell</div><div>" + countText + "</div></body></html>";
    }

    private static String nextListing(String id, String slug, String title) {
        return "<article><h2><a href=\"/en/jobs/" + id + "/" + slug + "/\">" + title + "</a></h2></article>";
    }

    private static final String NEXT_LISTINGS_PAGE = "<html><body>"
            + nextListing("744000153766639", "machine-learning-engineering-manager", "Machine Learning Engineering Manager")
            + nextListing("744000153766640", "senior-backend-engineer", "Senior Backend Engineer")
            + "<nav><a href=\"/en/jobs/overview/\">Overview</a></nav>"
            + "</body></html>";

    private static final String NEXT_DETAIL_HTML = """
            <html><head><title>Machine Learning Engineering Manager</title></head><body>
            <script type="application/ld+json">{"@context":"https://schema.org","@type":"WebSite","name":"SAP Careers"}</script>
            <script type="application/ld+json">
            {"@context":"https://schema.org","@type":"JobPosting","title":"Machine Learning Engineering Manager","description":"<p>Build <b>ML</b> platforms for enterprise customers.</p>","identifier":{"@type":"PropertyValue","name":"SAP Careers","value":"REF2091S"},"url":"https://jobs.sap.com/en/jobs/744000153766639/machine-learning-engineering-manager/","datePosted":"2026-09-15","employmentType":"FULL_TIME","hiringOrganization":{"@type":"Organization","name":"SAP"},"jobLocation":{"@type":"Place","name":"Paris, , France","address":{"@type":"PostalAddress","addressLocality":"Paris","addressCountry":"France"}}}
            </script>
            </body></html>
            """;

    private static final String NEXT_DETAIL_NO_LD = "<html><body><p>Detail without JSON-LD</p></body></html>";

    @Test
    void parseNextTotalCount_extractsJobsFoundText() {
        assertEquals(84, extractor.parseNextTotalCount("<div>84 jobs found</div>"));
        assertEquals(1, extractor.parseNextTotalCount("<div>1 job found</div>"));
        assertEquals(1234, extractor.parseNextTotalCount("<div>1,234 jobs found</div>"));
        assertEquals(0, extractor.parseNextTotalCount("<div>No results</div>"));
        assertEquals(0, extractor.parseNextTotalCount(null));
        assertEquals(0, extractor.parseNextTotalCount("   "));
    }

    @Test
    void parseNextTotalCount_returnsZeroForLegacyPagination() {
        String html = "<div>Showing 1 to 25 of 303</div>";
        assertEquals(303, extractor.parseTotalCount(html));
        assertEquals(0, extractor.parseNextTotalCount(html));
    }

    @Test
    void parseNextListings_extractsJobsAndSkipsNonJobAnchors() {
        String html = "<html><body>"
                + nextListing("744000153766639", "machine-learning-engineering-manager", "Machine Learning Engineering Manager")
                + nextListing("744000153766640", "senior-backend-engineer", "Senior Backend Engineer")
                + nextListing("744000153766639", "machine-learning-engineering-manager", "Machine Learning Engineering Manager")
                + "<nav><a href=\"/en/jobs/overview/\">Overview</a></nav>"
                + "<a href=\"/en/companies/sap/\">SAP</a>"
                + "</body></html>";

        List<SuccessFactorsStrategy.JobListing> listings =
                extractor.parseNextListings(html, "https://jobs.sap.com");

        assertEquals(2, listings.size());
        assertEquals("744000153766639", listings.get(0).externalId());
        assertEquals("Machine Learning Engineering Manager", listings.get(0).title());
        assertEquals("https://jobs.sap.com/en/jobs/744000153766639/machine-learning-engineering-manager/",
                listings.get(0).url());
        assertNull(listings.get(0).location());
        assertEquals("744000153766640", listings.get(1).externalId());
        assertEquals("Senior Backend Engineer", listings.get(1).title());
    }

    @Test
    void parseNextListings_returnsEmptyWhenNoJobAnchors() {
        List<SuccessFactorsStrategy.JobListing> listings =
                extractor.parseNextListings("<html><body><p>SPA shell</p></body></html>", "https://jobs.sap.com");
        assertTrue(listings.isEmpty());
    }

    @Test
    void parseNextJsonLd_extractsDescriptionDateAndLocation() {
        SuccessFactorsStrategy.JsonLdDetail detail = extractor.parseNextJsonLd(NEXT_DETAIL_HTML);

        assertEquals("Build ML platforms for enterprise customers.", detail.description());
        assertEquals(LocalDate.of(2026, 9, 15), detail.postedDate());
        assertEquals("Paris, , France", detail.location());
    }

    @Test
    void parseNextJsonLd_missingOrMalformed_returnsEmptyDetail() {
        SuccessFactorsStrategy.JsonLdDetail noScript =
                extractor.parseNextJsonLd("<html><body><p>No structured data</p></body></html>");
        assertNull(noScript.description());
        assertNull(noScript.postedDate());
        assertNull(noScript.location());

        SuccessFactorsStrategy.JsonLdDetail malformed = extractor.parseNextJsonLd(
                "<html><body><script type=\"application/ld+json\">{not-json</script></body></html>");
        assertNull(malformed.description());
        assertNull(malformed.postedDate());
        assertNull(malformed.location());

        SuccessFactorsStrategy.JsonLdDetail blank = extractor.parseNextJsonLd("");
        assertNull(blank.description());
    }

    @Test
    void parseNextJsonLd_truncatesLongDescription() {
        String longText = "x".repeat(12_000);
        String html = "<html><body><script type=\"application/ld+json\">"
                + "{\"@type\":\"JobPosting\",\"description\":\"<p>" + longText + "</p>\",\"datePosted\":\"2026-09-15\"}"
                + "</script></body></html>";

        SuccessFactorsStrategy.JsonLdDetail detail = extractor.parseNextJsonLd(html);
        assertNotNull(detail.description());
        assertEquals(10_000, detail.description().length());
    }

    @Test
    void fetch_nextStyleBranch_endToEnd() {
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.just(nextShell("2 jobs found")))
                .thenReturn(Mono.just(NEXT_LISTINGS_PAGE))
                .thenReturn(Mono.just(NEXT_DETAIL_HTML));

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://jobs.sap.com")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertEquals(ExtractionStatus.SUCCESS, result.status());
        assertEquals(2, result.totalFound());

        RawAggregatorJob first = result.jobs().get(0);
        assertEquals("744000153766639", first.externalId());
        assertEquals("Machine Learning Engineering Manager", first.title());
        assertEquals("https://jobs.sap.com/en/jobs/744000153766639/machine-learning-engineering-manager/",
                first.applyUrl());
        assertEquals("Build ML platforms for enterprise customers.", first.description());
        assertEquals(LocalDate.of(2026, 9, 15), first.postedDate());
        assertEquals("Paris, , France", first.location());

        RawAggregatorJob second = result.jobs().get(1);
        assertEquals("744000153766640", second.externalId());
        assertEquals("Senior Backend Engineer", second.title());
        assertEquals("https://jobs.sap.com/en/jobs/744000153766640/senior-backend-engineer/", second.applyUrl());

        // First page still fetched via the legacy search URL; listing pages use /en/jobs/?page=N
        verify(requestHeadersUriSpec).uri(argThat((String url) -> url != null
                && url.startsWith("https://jobs.sap.com/search/") && url.endsWith("startrow=0")));
        verify(requestHeadersUriSpec).uri("https://jobs.sap.com/en/jobs/?page=1");
    }

    @Test
    void fetch_nextStyleBranch_paginatesAcrossPages() {
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.just(nextShell("13 jobs found")))
                .thenReturn(Mono.just("<html><body>"
                        + nextListing("744000000000001", "page-one-job", "Page One Job")
                        + "</body></html>"))
                .thenReturn(Mono.just("<html><body>"
                        + nextListing("744000000000002", "page-two-job", "Page Two Job")
                        + "</body></html>"))
                .thenReturn(Mono.just(NEXT_DETAIL_NO_LD))
                .thenReturn(Mono.just(NEXT_DETAIL_NO_LD));

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://jobs.sap.com")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertEquals(ExtractionStatus.SUCCESS, result.status());
        assertEquals(2, result.totalFound());
        assertEquals("744000000000001", result.jobs().get(0).externalId());
        assertEquals("744000000000002", result.jobs().get(1).externalId());

        verify(requestHeadersUriSpec).uri("https://jobs.sap.com/en/jobs/?page=1");
        verify(requestHeadersUriSpec).uri("https://jobs.sap.com/en/jobs/?page=2");
        // ceil(13/12) = 2 pages: page 3 must never be requested
        verify(requestHeadersUriSpec, never()).uri(argThat((String url) -> url != null && url.contains("page=3")));
    }

    @Test
    void fetch_nextStyleBranch_stopsWhenPageYieldsNoListings() {
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.just(nextShell("25 jobs found")))
                .thenReturn(Mono.just("<html><body>"
                        + nextListing("744000000000003", "only-job", "Only Job")
                        + "</body></html>"))
                .thenReturn(Mono.just("<html><body></body></html>"))
                .thenReturn(Mono.just(NEXT_DETAIL_NO_LD));

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://jobs.sap.com")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertEquals(ExtractionStatus.SUCCESS, result.status());
        assertEquals(1, result.totalFound());
        assertEquals("744000000000003", result.jobs().get(0).externalId());

        verify(requestHeadersUriSpec).uri("https://jobs.sap.com/en/jobs/?page=2");
        // ceil(25/12) = 3 pages planned, but the empty page 2 stops the crawl before page 3
        verify(requestHeadersUriSpec, never()).uri(argThat((String url) -> url != null && url.contains("page=3")));
    }

    @Test
    void fetch_nextStyleBranch_missingJsonLd_keepsJobWithNullDescription() {
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.just(nextShell("1 job found")))
                .thenReturn(Mono.just("<html><body>"
                        + nextListing("744000000000004", "no-ld-job", "No LD Job")
                        + "</body></html>"))
                .thenReturn(Mono.just(NEXT_DETAIL_NO_LD));

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://jobs.sap.com")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertEquals(ExtractionStatus.SUCCESS, result.status());
        assertEquals(1, result.totalFound());
        assertEquals("744000000000004", result.jobs().get(0).externalId());
        assertNull(result.jobs().get(0).description());
        assertNull(result.jobs().get(0).postedDate());
        assertEquals("https://jobs.sap.com/en/jobs/744000000000004/no-ld-job/", result.jobs().get(0).applyUrl());
    }

    @Test
    void fetch_legacyShowingPattern_stillTakesLegacyPath() {
        // Literal jobs2web pagination text: the Next branch must not engage (no /en/jobs/ requests)
        // and pagination must stay on startrow offsets.
        String legacyPage = """
                <html><body>
                <div>Showing 1 to 25 of 303</div>
                <table>
                  <tr><td><a href="/job/Berlin-Dev-10557/111/">Developer</a></td><td>Berlin</td></tr>
                </table>
                </body></html>
                """;
        String legacyBlank = "<html><body></body></html>";
        String legacyDetail = "<html><body><div class=\"jobdescription\"><p>Legacy detail</p></div></body></html>";

        // ceil(303/25) = 13 search pages: 1 real page + 12 blank pages + 1 detail fetch
        var stub = when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(legacyPage));
        for (int i = 0; i < 12; i++) {
            stub.thenReturn(Mono.just(legacyBlank));
        }
        stub.thenReturn(Mono.just(legacyDetail));

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://jobs.example.com")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        FetchResult result = extractor.fetch(FetchContext.forEndpoint(endpoint));

        assertEquals(ExtractionStatus.SUCCESS, result.status());
        assertEquals(1, result.totalFound());
        assertEquals("111", result.jobs().get(0).externalId());
        assertTrue(result.jobs().get(0).applyUrl().startsWith("https://jobs.example.com/job/"));
        assertTrue(result.jobs().get(0).description().contains("Legacy detail"));

        verify(requestHeadersUriSpec, never()).uri(argThat((String url) -> url != null && url.contains("/en/jobs/")));
        verify(requestHeadersUriSpec).uri(argThat((String url) -> url != null && url.endsWith("&startrow=25")));
    }
}
