package dev.jobhub.extraction;

import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.model.enums.AtsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SuccessFactorsExtractorTest {

    private WebClient webClient;
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;
    private WebClient.RequestHeadersSpec requestHeadersSpec;
    private WebClient.ResponseSpec responseSpec;
    private SuccessFactorsExtractor extractor;

    @BeforeEach
    void setUp() {
        webClient = mock(WebClient.class);
        requestHeadersUriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
        responseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);

        extractor = new SuccessFactorsExtractor(webClient);
    }

    @Test
    void supportedTypes_returnsSuccessFactors() {
        assertEquals(Set.of(AtsType.SUCCESSFACTORS), extractor.supportedTypes());
    }

    @Test
    void canExtract_returnsTrueWhenUrlPresent() {
        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://jobs.sap.com")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();
        assertTrue(extractor.canExtract(endpoint));
    }

    @Test
    void canExtract_returnsFalseWhenUrlNull() {
        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url(null)
                .atsType(AtsType.SUCCESSFACTORS)
                .build();
        assertFalse(extractor.canExtract(endpoint));
    }

    @Test
    void canExtract_returnsFalseWhenUrlBlank() {
        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("   ")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();
        assertFalse(extractor.canExtract(endpoint));
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

        List<SuccessFactorsExtractor.JobListing> listings = extractor.parseListings(html, "https://jobs.sap.com");

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

        List<SuccessFactorsExtractor.JobListing> listings = extractor.parseListings(html, "https://jobs.example.com");
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

        List<SuccessFactorsExtractor.JobListing> listings = extractor.parseListings(html, "https://jobs.sap.com");
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

        ExtractionResult result = extractor.extract(endpoint);
        assertEquals(0, result.totalFound());
    }

    @Test
    void extract_returnsEmptyOnNullResponse() {
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.empty());

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .url("https://jobs.example.com")
                .atsType(AtsType.SUCCESSFACTORS)
                .build();

        ExtractionResult result = extractor.extract(endpoint);
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

        ExtractionResult result = extractor.extract(endpoint);
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

        ExtractionResult result = extractor.extract(endpoint);
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

        ExtractionResult result = extractor.extract(endpoint);

        assertEquals(2, result.totalFound());
        assertEquals(2, result.jobs().size());

        RawJobData first = result.jobs().get(0);
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

        ExtractionResult result = extractor.extract(endpoint);

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

        ExtractionResult result = extractor.extract(endpoint);
        assertEquals(1, result.totalFound());
        // URL should not have double slashes
        assertTrue(result.jobs().get(0).applyUrl().startsWith("https://jobs.example.com/job/"));
    }
}
