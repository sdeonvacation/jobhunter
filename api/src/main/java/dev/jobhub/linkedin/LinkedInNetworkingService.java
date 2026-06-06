package dev.jobhub.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobhub.model.Company;
import dev.jobhub.repository.CompanyRepository;
import dev.jobhub.repository.OutreachContactRepository;
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
