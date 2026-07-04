package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobhunter.model.Company;
import dev.jobhunter.people.model.enums.ContactDiscoverySource;
import dev.jobhunter.repository.CompanyRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class LinkedInNetworkingService {

    private static final int DEFAULT_DAILY_LIMIT = 5;
    private static final Duration COOLDOWN_PERIOD = Duration.ofDays(7);

    private final HttpMcpClient httpMcpClient;
    private final LinkedInRateLimiter rateLimiter;
    private final OutreachContactRepository contactRepository;
    private final LinkedInMcpProperties mcpProperties;
    private final CompanyRepository companyRepository;

    public LinkedInNetworkingService(HttpMcpClient httpMcpClient,
                                     LinkedInRateLimiter rateLimiter,
                                     OutreachContactRepository contactRepository,
                                     LinkedInMcpProperties mcpProperties,
                                     CompanyRepository companyRepository) {
        this.httpMcpClient = httpMcpClient;
        this.rateLimiter = rateLimiter;
        this.contactRepository = contactRepository;
        this.mcpProperties = mcpProperties;
        this.companyRepository = companyRepository;
    }

    /**
     * Search for contacts at a company matching title keywords.
     */
    public List<OutreachContact> findContacts(UUID companyId, List<String> titleKeywords) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + companyId));

        if (!rateLimiter.acquire(ToolCategory.SEARCH)) {
            log.warn("Rate limit reached for SEARCH, cannot search contacts for company '{}'", company.getName());
            return List.of();
        }

        Map<String, Object> params = Map.of(
                "company", company.getName(),
                "keywords", String.join(" ", titleKeywords)
        );

        JsonNode response = httpMcpClient.callTool("search_people", params);
        List<OutreachContact> contacts = parseContactResults(response, company);

        contactRepository.saveAll(contacts);
        log.info("Found {} contacts at '{}'", contacts.size(), company.getName());
        return contacts;
    }

    /**
     * Search for people by keywords and location (school/alumni search).
     * Keywords can include school names, roles, etc.
     */
    public List<OutreachContact> searchByKeywords(String keywords, String location, List<String> network) {
        if (!rateLimiter.acquire(ToolCategory.SEARCH)) {
            log.warn("Rate limit reached for SEARCH, cannot search by keywords '{}'", keywords);
            return List.of();
        }

        Map<String, Object> params = new HashMap<>();
        params.put("keywords", keywords);
        if (location != null && !location.isBlank()) {
            params.put("location", location);
        }
        if (network != null && !network.isEmpty()) {
            params.put("network", network);
        }

        JsonNode response = httpMcpClient.callTool("search_people", params);
        List<OutreachContact> contacts = parseKeywordSearchResults(response);

        contactRepository.saveAll(contacts);
        log.info("Found {} contacts for keywords '{}'", contacts.size(), keywords);
        return contacts;
    }

    /**
     * Send a connection request to a contact.
     */
    public ConnectionResult connect(UUID contactId, String note) {
        OutreachContact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + contactId));

        if (contact.getConnectionStatus() == ConnectionStatus.CONNECTED) {
            return new ConnectionResult(ConnectionResult.Status.ALREADY_CONNECTED, "Already connected");
        }

        if (getDailyConnectionsRemaining() <= 0) {
            return new ConnectionResult(ConnectionResult.Status.DAILY_LIMIT_REACHED,
                    "Daily connection limit reached");
        }

        if (!rateLimiter.acquire(ToolCategory.ACTION)) {
            return new ConnectionResult(ConnectionResult.Status.FAILED, "Rate limit reached");
        }

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("linkedin_url", contact.getLinkedinUrl());
            if (note != null && !note.isBlank()) {
                params.put("note", note);
            }

            httpMcpClient.callTool("connect_with_person", params);

            contact.setConnectionStatus(ConnectionStatus.PENDING);
            contact.setConnectionSentAt(LocalDateTime.now());
            contactRepository.save(contact);

            log.info("Connection request sent to '{}'", contact.getPersonName());
            return new ConnectionResult(ConnectionResult.Status.SENT, "Connection request sent");

        } catch (McpClientException e) {
            log.error("Failed to connect with '{}': {}", contact.getPersonName(), e.getMessage());
            return new ConnectionResult(ConnectionResult.Status.FAILED, e.getMessage());
        }
    }

    /**
     * Send a message to a connected contact.
     */
    public MessageResult sendMessage(UUID contactId, String message) {
        OutreachContact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + contactId));

        if (contact.getConnectionStatus() != ConnectionStatus.CONNECTED) {
            return new MessageResult(MessageResult.Status.NOT_CONNECTED,
                    "Cannot message: not connected with " + contact.getPersonName());
        }

        if (isCooldownActive(contact)) {
            return new MessageResult(MessageResult.Status.COOLDOWN_ACTIVE,
                    "Cooldown active until " + contact.getLastContactedAt().plus(COOLDOWN_PERIOD));
        }

        if (!rateLimiter.acquire(ToolCategory.ACTION)) {
            return new MessageResult(MessageResult.Status.FAILED, "Rate limit reached");
        }

        try {
            Map<String, Object> params = Map.of(
                    "linkedin_url", contact.getLinkedinUrl(),
                    "message", message
            );

            httpMcpClient.callTool("send_message", params);

            contact.setLastContactedAt(LocalDateTime.now());
            contactRepository.save(contact);

            log.info("Message sent to '{}'", contact.getPersonName());
            return new MessageResult(MessageResult.Status.SENT, "Message sent");

        } catch (McpClientException e) {
            log.error("Failed to send message to '{}': {}", contact.getPersonName(), e.getMessage());
            return new MessageResult(MessageResult.Status.FAILED, e.getMessage());
        }
    }

    /**
     * Get remaining daily connection requests.
     */
    public int getDailyConnectionsRemaining() {
        long sentToday = contactRepository.countConnectionsSentToday(
                LocalDateTime.of(LocalDate.now(), LocalTime.MIDNIGHT));
        return Math.max(0, DEFAULT_DAILY_LIMIT - (int) sentToday);
    }

    private boolean isCooldownActive(OutreachContact contact) {
        if (contact.getLastContactedAt() == null) {
            return false;
        }
        return contact.getLastContactedAt().plus(COOLDOWN_PERIOD).isAfter(LocalDateTime.now());
    }

    private List<OutreachContact> parseContactResults(JsonNode response, Company company) {
        List<OutreachContact> contacts = new ArrayList<>();

        JsonNode people = response.path("people");
        if (!people.isArray()) {
            people = response.isArray() ? response : response.path("results");
        }

        if (!people.isArray()) {
            return contacts;
        }

        for (JsonNode person : people) {
            String name = getTextOrNull(person, "name", "full_name");
            String linkedinUrl = getTextOrNull(person, "linkedin_url", "url", "profile_url");
            String title = getTextOrNull(person, "title", "headline");

            if (name != null && linkedinUrl != null) {
                // Check if contact already exists
                Optional<OutreachContact> existing = contactRepository.findByLinkedinUrl(linkedinUrl);
                if (existing.isPresent()) {
                    contacts.add(existing.get());
                } else {
                    contacts.add(OutreachContact.builder()
                            .company(company)
                            .linkedinUrl(linkedinUrl)
                            .personName(name)
                            .title(title)
                            .connectionStatus(ConnectionStatus.NONE)
                            .build());
                }
            }
        }

        return contacts;
    }

    private List<OutreachContact> parseKeywordSearchResults(JsonNode response) {
        List<OutreachContact> contacts = new ArrayList<>();

        // Try structuredContent format (linkedin-scraper-mcp response)
        JsonNode structuredContent = response.path("structuredContent");
        JsonNode references = structuredContent.path("references").path("search_results");

        if (references.isArray() && !references.isEmpty()) {
            String sectionsText = structuredContent.path("sections").path("search_results").asText("");
            List<PersonInfo> infos = parseSectionsText(sectionsText);

            for (int i = 0; i < references.size(); i++) {
                JsonNode ref = references.get(i);
                if (!"person".equals(ref.path("kind").asText())) continue;

                String name = ref.path("text").asText(null);
                String relativeUrl = ref.path("url").asText(null);
                if (name == null || relativeUrl == null) continue;

                String linkedinUrl = "https://www.linkedin.com" + relativeUrl;
                String title = (i < infos.size()) ? infos.get(i).headline() : null;
                String location = (i < infos.size()) ? infos.get(i).location() : null;

                Optional<OutreachContact> existing = contactRepository.findByLinkedinUrl(linkedinUrl);
                if (existing.isPresent()) {
                    contacts.add(existing.get());
                } else {
                    contacts.add(OutreachContact.builder()
                            .linkedinUrl(linkedinUrl)
                            .personName(name)
                            .title(title)
                            .location(location)
                            .connectionStatus(ConnectionStatus.NONE)
                            .discoveredVia(ContactDiscoverySource.ALUMNI_SEARCH)
                            .build());
                }
            }
            return contacts;
        }

        // Fallback: old format (people/results array)
        JsonNode people = response.path("people");
        if (!people.isArray()) {
            people = response.isArray() ? response : response.path("results");
        }

        if (people.isArray()) {
            for (JsonNode person : people) {
                String name = getTextOrNull(person, "name", "full_name");
                String linkedinUrl = getTextOrNull(person, "linkedin_url", "url", "profile_url");
                String title = getTextOrNull(person, "title", "headline");
                String personLocation = getTextOrNull(person, "location");

                if (name != null && linkedinUrl != null) {
                    Optional<OutreachContact> existing = contactRepository.findByLinkedinUrl(linkedinUrl);
                    if (existing.isPresent()) {
                        contacts.add(existing.get());
                    } else {
                        contacts.add(OutreachContact.builder()
                                .linkedinUrl(linkedinUrl)
                                .personName(name)
                                .title(title)
                                .location(personLocation)
                                .connectionStatus(ConnectionStatus.NONE)
                                .discoveredVia(ContactDiscoverySource.ALUMNI_SEARCH)
                                .build());
                    }
                }
            }
        }

        return contacts;
    }

    private record PersonInfo(String headline, String location) {}

    private List<PersonInfo> parseSectionsText(String text) {
        List<PersonInfo> infos = new ArrayList<>();
        if (text == null || text.isBlank()) return infos;

        // Each person block starts with "Name • degree" pattern
        // Split on double-newline that precedes a name line containing •
        String[] blocks = text.split("\\n\\n(?=[^\\n]+ \u2022 )");

        for (String block : blocks) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) continue;

            // Person block lines separated by \n\n:
            // [0] = "Name • 3rd+"
            // [1] = headline
            // [2] = location
            // [3] = "Connect" or "Follow"
            // [4] = "Current: ..." (optional)
            String[] parts = trimmed.split("\n\n");
            String headline = parts.length > 1 ? parts[1].trim() : null;
            String location = parts.length > 2 ? parts[2].trim() : null;

            // Skip if extracted "location" is actually a button label
            if (location != null && (location.equals("Connect") || location.equals("Follow"))) {
                location = null;
            }

            infos.add(new PersonInfo(headline, location));
        }

        return infos;
    }

    private String getTextOrNull(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    // Result records

    public record ConnectionResult(Status status, String message) {
        public enum Status {SENT, DAILY_LIMIT_REACHED, ALREADY_CONNECTED, FAILED}
    }

    public record MessageResult(Status status, String message) {
        public enum Status {SENT, COOLDOWN_ACTIVE, NOT_CONNECTED, FAILED}
    }
}
