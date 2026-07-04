package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jobhunter.people.model.enums.ContactDiscoverySource;
import dev.jobhunter.repository.OutreachContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LinkedInNetworkingServiceSearchByKeywordsTest {

    private HttpMcpClient httpMcpClient;
    private LinkedInRateLimiter rateLimiter;
    private OutreachContactRepository contactRepository;
    private LinkedInNetworkingService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        httpMcpClient = mock(HttpMcpClient.class);
        rateLimiter = mock(LinkedInRateLimiter.class);
        contactRepository = mock(OutreachContactRepository.class);
        var mcpProperties = mock(LinkedInMcpProperties.class);
        var companyRepository = mock(dev.jobhunter.repository.CompanyRepository.class);

        service = new LinkedInNetworkingService(
                httpMcpClient, rateLimiter, contactRepository, mcpProperties, companyRepository);
    }

    @Test
    @DisplayName("searchByKeywords returns empty list when rate limited")
    void shouldReturnEmptyWhenRateLimited() {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(false);

        List<OutreachContact> result = service.searchByKeywords("IIT Kanpur", "Germany", null);

        assertThat(result).isEmpty();
        verifyNoInteractions(httpMcpClient);
    }

    @Test
    @DisplayName("searchByKeywords passes keywords and location to MCP client")
    void shouldPassParamsToMcpClient() {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_people"), any())).thenReturn(mapper.createObjectNode());

        service.searchByKeywords("IIT Kanpur software engineer", "Germany", List.of("S", "O"));

        verify(httpMcpClient).callTool(eq("search_people"), argThat(params -> {
            assertThat(params.get("keywords")).isEqualTo("IIT Kanpur software engineer");
            assertThat(params.get("location")).isEqualTo("Germany");
            assertThat(params.get("network")).isEqualTo(List.of("S", "O"));
            return true;
        }));
    }

    @Test
    @DisplayName("searchByKeywords omits null/blank location and empty network")
    void shouldOmitNullLocationAndEmptyNetwork() {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_people"), any())).thenReturn(mapper.createObjectNode());

        service.searchByKeywords("alumni", null, List.of());

        verify(httpMcpClient).callTool(eq("search_people"), argThat(params -> {
            assertThat(params).containsKey("keywords");
            assertThat(params).doesNotContainKey("location");
            assertThat(params).doesNotContainKey("network");
            return true;
        }));
    }

    @Test
    @DisplayName("searchByKeywords parses people array and saves contacts")
    void shouldParseAndSaveContacts() {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(contactRepository.findByLinkedinUrl(any())).thenReturn(Optional.empty());

        ObjectNode response = mapper.createObjectNode();
        ArrayNode people = response.putArray("people");
        ObjectNode person = people.addObject();
        person.put("name", "John Doe");
        person.put("linkedin_url", "https://linkedin.com/in/johndoe");
        person.put("title", "Senior Engineer");
        person.put("location", "Berlin, Germany");

        when(httpMcpClient.callTool(eq("search_people"), any())).thenReturn(response);

        List<OutreachContact> result = service.searchByKeywords("IIT", "Germany", null);

        assertThat(result).hasSize(1);
        OutreachContact contact = result.get(0);
        assertThat(contact.getPersonName()).isEqualTo("John Doe");
        assertThat(contact.getLinkedinUrl()).isEqualTo("https://linkedin.com/in/johndoe");
        assertThat(contact.getTitle()).isEqualTo("Senior Engineer");
        assertThat(contact.getLocation()).isEqualTo("Berlin, Germany");
        assertThat(contact.getConnectionStatus()).isEqualTo(ConnectionStatus.NONE);
        assertThat(contact.getDiscoveredVia()).isEqualTo(ContactDiscoverySource.ALUMNI_SEARCH);
        assertThat(contact.getCompany()).isNull();

        verify(contactRepository).saveAll(result);
    }

    @Test
    @DisplayName("searchByKeywords deduplicates by linkedin URL")
    void shouldDeduplicateByLinkedinUrl() {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);

        OutreachContact existing = OutreachContact.builder()
                .linkedinUrl("https://linkedin.com/in/existing")
                .personName("Existing User")
                .connectionStatus(ConnectionStatus.CONNECTED)
                .build();
        when(contactRepository.findByLinkedinUrl("https://linkedin.com/in/existing"))
                .thenReturn(Optional.of(existing));

        ObjectNode response = mapper.createObjectNode();
        ArrayNode people = response.putArray("people");
        ObjectNode person = people.addObject();
        person.put("name", "Existing User");
        person.put("linkedin_url", "https://linkedin.com/in/existing");
        person.put("title", "Some Title");

        when(httpMcpClient.callTool(eq("search_people"), any())).thenReturn(response);

        List<OutreachContact> result = service.searchByKeywords("test", null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(existing);
    }

    @Test
    @DisplayName("searchByKeywords skips entries without name or URL")
    void shouldSkipEntriesWithoutNameOrUrl() {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(contactRepository.findByLinkedinUrl(any())).thenReturn(Optional.empty());

        ObjectNode response = mapper.createObjectNode();
        ArrayNode people = response.putArray("people");

        // Missing URL
        ObjectNode noUrl = people.addObject();
        noUrl.put("name", "No URL Person");

        // Missing name
        ObjectNode noName = people.addObject();
        noName.put("linkedin_url", "https://linkedin.com/in/noname");

        // Valid
        ObjectNode valid = people.addObject();
        valid.put("name", "Valid Person");
        valid.put("linkedin_url", "https://linkedin.com/in/valid");

        when(httpMcpClient.callTool(eq("search_people"), any())).thenReturn(response);

        List<OutreachContact> result = service.searchByKeywords("test", null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPersonName()).isEqualTo("Valid Person");
    }

    @Test
    @DisplayName("searchByKeywords handles alternative response formats")
    void shouldHandleAlternativeResponseFormats() {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(contactRepository.findByLinkedinUrl(any())).thenReturn(Optional.empty());

        // Response as direct array
        ArrayNode response = mapper.createArrayNode();
        ObjectNode person = response.addObject();
        person.put("full_name", "Array Person");
        person.put("url", "https://linkedin.com/in/arrayperson");
        person.put("headline", "Developer");

        when(httpMcpClient.callTool(eq("search_people"), any())).thenReturn(response);

        List<OutreachContact> result = service.searchByKeywords("test", null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPersonName()).isEqualTo("Array Person");
        assertThat(result.get(0).getTitle()).isEqualTo("Developer");
    }

    @Test
    @DisplayName("searchByKeywords returns empty when response has no people")
    void shouldReturnEmptyWhenNoPeopleInResponse() {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_people"), any())).thenReturn(mapper.createObjectNode());

        List<OutreachContact> result = service.searchByKeywords("nonexistent", "Mars", null);

        assertThat(result).isEmpty();
        verify(contactRepository).saveAll(List.of());
    }

    @Test
    @DisplayName("searchByKeywords parses structuredContent references format")
    void shouldParseStructuredContentReferences() {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(contactRepository.findByLinkedinUrl(any())).thenReturn(Optional.empty());

        ObjectNode response = mapper.createObjectNode();
        ObjectNode structured = response.putObject("structuredContent");

        // sections
        ObjectNode sections = structured.putObject("sections");
        sections.put("search_results",
                "Aastha S. \u2022 3rd+\n\nSoftware Engineer @ CORRECTIV | IIT-Kanpur\n\nGermany\n\nConnect\n\nCurrent: Software Engineer at CORRECTIV\n\n" +
                "Sambhrant Maurya \u2022 3rd+\n\nSoftware Engineer | Cloud & AI\n\nBerlin, Berlin, Germany\n\nConnect\n\nCurrent: Engineer at Startup");

        // references
        ObjectNode references = structured.putObject("references");
        ArrayNode refs = references.putArray("search_results");
        ObjectNode ref1 = refs.addObject();
        ref1.put("kind", "person");
        ref1.put("url", "/in/aasthas9/");
        ref1.put("text", "Aastha S.");
        ref1.put("context", "search result");
        ObjectNode ref2 = refs.addObject();
        ref2.put("kind", "person");
        ref2.put("url", "/in/mauryasam/");
        ref2.put("text", "Sambhrant Maurya");
        ref2.put("context", "search result");

        when(httpMcpClient.callTool(eq("search_people"), any())).thenReturn(response);

        List<OutreachContact> result = service.searchByKeywords("IIT Kanpur", "Germany", null);

        assertThat(result).hasSize(2);

        OutreachContact first = result.get(0);
        assertThat(first.getPersonName()).isEqualTo("Aastha S.");
        assertThat(first.getLinkedinUrl()).isEqualTo("https://www.linkedin.com/in/aasthas9/");
        assertThat(first.getTitle()).isEqualTo("Software Engineer @ CORRECTIV | IIT-Kanpur");
        assertThat(first.getLocation()).isEqualTo("Germany");
        assertThat(first.getDiscoveredVia()).isEqualTo(ContactDiscoverySource.ALUMNI_SEARCH);

        OutreachContact second = result.get(1);
        assertThat(second.getPersonName()).isEqualTo("Sambhrant Maurya");
        assertThat(second.getLinkedinUrl()).isEqualTo("https://www.linkedin.com/in/mauryasam/");
        assertThat(second.getTitle()).isEqualTo("Software Engineer | Cloud & AI");
        assertThat(second.getLocation()).isEqualTo("Berlin, Berlin, Germany");
    }

    @Test
    @DisplayName("searchByKeywords skips non-person references")
    void shouldSkipNonPersonReferences() {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(contactRepository.findByLinkedinUrl(any())).thenReturn(Optional.empty());

        ObjectNode response = mapper.createObjectNode();
        ObjectNode structured = response.putObject("structuredContent");
        structured.putObject("sections").put("search_results", "Some Person \u2022 3rd+\n\nDeveloper\n\nBerlin\n\nConnect");

        ObjectNode references = structured.putObject("references");
        ArrayNode refs = references.putArray("search_results");

        // Non-person reference (e.g. company)
        ObjectNode companyRef = refs.addObject();
        companyRef.put("kind", "company");
        companyRef.put("url", "/company/acme/");
        companyRef.put("text", "Acme Corp");

        // Valid person
        ObjectNode personRef = refs.addObject();
        personRef.put("kind", "person");
        personRef.put("url", "/in/someperson/");
        personRef.put("text", "Some Person");

        when(httpMcpClient.callTool(eq("search_people"), any())).thenReturn(response);

        List<OutreachContact> result = service.searchByKeywords("test", null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPersonName()).isEqualTo("Some Person");
    }

    @Test
    @DisplayName("searchByKeywords deduplicates structuredContent contacts by URL")
    void shouldDeduplicateStructuredContentContacts() {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);

        OutreachContact existing = OutreachContact.builder()
                .linkedinUrl("https://www.linkedin.com/in/existing/")
                .personName("Existing User")
                .connectionStatus(ConnectionStatus.CONNECTED)
                .build();
        when(contactRepository.findByLinkedinUrl("https://www.linkedin.com/in/existing/"))
                .thenReturn(Optional.of(existing));

        ObjectNode response = mapper.createObjectNode();
        ObjectNode structured = response.putObject("structuredContent");
        structured.putObject("sections").put("search_results", "Existing User \u2022 2nd\n\nEngineer\n\nMunich");
        ObjectNode references = structured.putObject("references");
        ArrayNode refs = references.putArray("search_results");
        ObjectNode ref = refs.addObject();
        ref.put("kind", "person");
        ref.put("url", "/in/existing/");
        ref.put("text", "Existing User");

        when(httpMcpClient.callTool(eq("search_people"), any())).thenReturn(response);

        List<OutreachContact> result = service.searchByKeywords("test", null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(existing);
    }

    @Test
    @DisplayName("searchByKeywords handles structuredContent with empty sections text")
    void shouldHandleEmptySectionsText() {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(contactRepository.findByLinkedinUrl(any())).thenReturn(Optional.empty());

        ObjectNode response = mapper.createObjectNode();
        ObjectNode structured = response.putObject("structuredContent");
        structured.putObject("sections").put("search_results", "");

        ObjectNode references = structured.putObject("references");
        ArrayNode refs = references.putArray("search_results");
        ObjectNode ref = refs.addObject();
        ref.put("kind", "person");
        ref.put("url", "/in/someone/");
        ref.put("text", "Someone");

        when(httpMcpClient.callTool(eq("search_people"), any())).thenReturn(response);

        List<OutreachContact> result = service.searchByKeywords("test", null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPersonName()).isEqualTo("Someone");
        assertThat(result.get(0).getLinkedinUrl()).isEqualTo("https://www.linkedin.com/in/someone/");
        // No headline/location available from empty sections
        assertThat(result.get(0).getTitle()).isNull();
        assertThat(result.get(0).getLocation()).isNull();
    }

    @Test
    @DisplayName("searchByKeywords skips references with null name or URL")
    void shouldSkipReferencesWithNullNameOrUrl() {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(contactRepository.findByLinkedinUrl(any())).thenReturn(Optional.empty());

        ObjectNode response = mapper.createObjectNode();
        ObjectNode structured = response.putObject("structuredContent");
        structured.putObject("sections").put("search_results", "");

        ObjectNode references = structured.putObject("references");
        ArrayNode refs = references.putArray("search_results");

        // Missing text
        ObjectNode noText = refs.addObject();
        noText.put("kind", "person");
        noText.put("url", "/in/notext/");

        // Missing url
        ObjectNode noUrl = refs.addObject();
        noUrl.put("kind", "person");
        noUrl.put("text", "No URL");

        // Valid
        ObjectNode valid = refs.addObject();
        valid.put("kind", "person");
        valid.put("url", "/in/valid/");
        valid.put("text", "Valid Person");

        when(httpMcpClient.callTool(eq("search_people"), any())).thenReturn(response);

        List<OutreachContact> result = service.searchByKeywords("test", null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPersonName()).isEqualTo("Valid Person");
    }
}
