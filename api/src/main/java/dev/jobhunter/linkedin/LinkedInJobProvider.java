package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobhunter.discovery.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Discovery provider that searches LinkedIn jobs via MCP tool calls.
 * Implements circuit breaker: consecutive failures → disable until cooldown.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class LinkedInJobProvider implements DiscoveryProvider {

    private final HttpMcpClient httpMcpClient;
    private final DiscoveryProperties properties;
    private final LinkedInRateLimiter rateLimiter;
    private final LinkedInMcpProperties mcpProperties;

    // Stats tracking
    private final AtomicInteger totalCalls = new AtomicInteger(0);
    private final AtomicInteger successfulCalls = new AtomicInteger(0);
    private final AtomicInteger companiesDiscovered = new AtomicInteger(0);
    private final AtomicReference<LocalDateTime> lastCallAt = new AtomicReference<>();
    private final AtomicLong totalLatencyMs = new AtomicLong(0);

    // Circuit breaker state
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile Instant circuitOpenedAt = null;

    public LinkedInJobProvider(HttpMcpClient httpMcpClient,
                               DiscoveryProperties properties,
                               LinkedInRateLimiter rateLimiter,
                               LinkedInMcpProperties mcpProperties) {
        this.httpMcpClient = httpMcpClient;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.mcpProperties = mcpProperties;
    }

    @Override
    public String name() {
        return "linkedin";
    }

    @Override
    public List<DiscoveredCompany> discover(DiscoveryQuery query) {
        if (!isHealthy()) {
            log.debug("LinkedIn provider not healthy, skipping discovery");
            return List.of();
        }

        var config = properties.providers().get("linkedin");
        if (config == null || !config.enabled()) {
            log.debug("LinkedIn provider not configured or disabled");
            return List.of();
        }

        totalCalls.incrementAndGet();
        lastCallAt.set(LocalDateTime.now());
        Instant start = Instant.now();

        try {
            List<DiscoveredCompany> results = new ArrayList<>();

            for (String keyword : query.keywords()) {
                for (String location : query.locations()) {
                    if (!rateLimiter.acquire(ToolCategory.SEARCH)) {
                        log.warn("LinkedIn rate limit reached for SEARCH, stopping discovery");
                        return results;
                    }

                    Map<String, Object> params = Map.of(
                            "keywords", keyword,
                            "location", location,
                            "date_posted", "week"
                    );

                    JsonNode response = httpMcpClient.callTool("search_jobs", params);
                    results.addAll(parseJobSearchResponse(response));
                }
            }

            long elapsed = Duration.between(start, Instant.now()).toMillis();
            totalLatencyMs.addAndGet(elapsed);
            successfulCalls.incrementAndGet();
            companiesDiscovered.addAndGet(results.size());
            consecutiveFailures.set(0);

            log.info("LinkedIn discovered {} companies in {}ms", results.size(), elapsed);
            return results;

        } catch (McpClientException e) {
            handleFailure(start, e);
            return List.of();
        } catch (Exception e) {
            handleFailure(start, e);
            return List.of();
        }
    }

    @Override
    public boolean isHealthy() {
        if (!mcpProperties.enabled()) {
            return false;
        }

        if (circuitOpenedAt != null) {
            Duration cooldown = Duration.ofMinutes(mcpProperties.circuitBreaker().cooldownMinutes());
            if (Instant.now().isBefore(circuitOpenedAt.plus(cooldown))) {
                return false;
            }
            // Cooldown elapsed, reset circuit breaker
            circuitOpenedAt = null;
            consecutiveFailures.set(0);
            log.info("LinkedIn circuit breaker reset after cooldown");
        }

        return httpMcpClient.isSessionValid();
    }

    @Override
    public DiscoveryProviderStats getStats() {
        int total = totalCalls.get();
        Duration avgLatency = total > 0
                ? Duration.ofMillis(totalLatencyMs.get() / total)
                : Duration.ZERO;

        return new DiscoveryProviderStats(
                total,
                successfulCalls.get(),
                companiesDiscovered.get(),
                lastCallAt.get(),
                avgLatency
        );
    }

    private List<DiscoveredCompany> parseJobSearchResponse(JsonNode response) {
        List<DiscoveredCompany> companies = new ArrayList<>();

        // MCP response format: {content: [{type:"text", text:"..."}], structuredContent: {sections: {search_results: "..."}, job_ids: [...]}}
        JsonNode structuredContent = response.path("structuredContent");
        String searchText = structuredContent.path("sections").path("search_results").asText("");

        if (searchText.isBlank()) {
            return companies;
        }

        String[] lines = searchText.split("\n");

        // Strategy: find location lines (contain "(Hybrid)", "(Remote)", "(On-site)")
        // Line before location = company, line(s) before that = job title
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (isLocationLine(line) && i >= 2) {
                String companyName = lines[i - 1].trim();
                // Skip "with verification" lines to find the actual title
                int titleIdx = i - 2;
                while (titleIdx >= 0 && lines[titleIdx].trim().endsWith("with verification")) {
                    titleIdx--;
                }
                String title = titleIdx >= 0 ? lines[titleIdx].trim() : "";

                // Skip noise
                if (companyName.isEmpty() || companyName.contains("results")
                        || companyName.startsWith("Set alert") || companyName.startsWith("Jump to")
                        || companyName.endsWith("with verification")) {
                    continue;
                }

                companies.add(new DiscoveredCompany(
                        companyName,
                        title,
                        null,
                        null
                ));
            }
        }

        log.debug("Parsed {} companies from search_results text ({} chars)", companies.size(), searchText.length());
        return companies;
    }

    private boolean isLocationLine(String line) {
        return line.contains("(Hybrid)") || line.contains("(Remote)") || line.contains("(On-site)")
                || line.contains("(On-Site)") || line.contains("(Onsite)");
    }

    private void handleFailure(Instant start, Exception e) {
        long elapsed = Duration.between(start, Instant.now()).toMillis();
        totalLatencyMs.addAndGet(elapsed);

        int failures = consecutiveFailures.incrementAndGet();
        int threshold = mcpProperties.circuitBreaker().failureThreshold();

        if (failures >= threshold) {
            circuitOpenedAt = Instant.now();
            log.warn("LinkedIn circuit breaker opened after {} consecutive failures", failures);
        }

        log.error("LinkedIn discovery failed: {}", e.getMessage());
    }
}
