package dev.jobhunter.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobhunter.mcp.McpSidecarClient;
import lombok.extern.slf4j.Slf4j;
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
 * Discovery provider that calls jobspy-mcp sidecar via MCP JSON-RPC over stdio.
 * Implements circuit breaker: 3 consecutive failures → disable for 1 hour.
 */
@Slf4j
@Component
public class JobSpyProvider implements DiscoveryProvider {

    private static final int CIRCUIT_BREAKER_THRESHOLD = 3;
    private static final Duration CIRCUIT_BREAKER_COOLDOWN = Duration.ofHours(1);
    private static final String MCP_COMMAND = "npx";
    private static final List<String> MCP_ARGS = List.of("-y", "jobspy-mcp");
    private static final String TOOL_NAME = "scrape_jobs";

    private final McpSidecarClient mcpClient;
    private final DiscoveryProperties properties;

    // Stats tracking
    private final AtomicInteger totalCalls = new AtomicInteger(0);
    private final AtomicInteger successfulCalls = new AtomicInteger(0);
    private final AtomicInteger companiesDiscovered = new AtomicInteger(0);
    private final AtomicReference<LocalDateTime> lastCallAt = new AtomicReference<>();
    private final AtomicLong totalLatencyMs = new AtomicLong(0);

    // Circuit breaker state
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile Instant circuitOpenedAt = null;

    public JobSpyProvider(McpSidecarClient mcpClient, DiscoveryProperties properties) {
        this.mcpClient = mcpClient;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "jobspy";
    }

    @Override
    public List<DiscoveredCompany> discover(DiscoveryQuery query) {
        if (!isHealthy()) {
            log.debug("JobSpy provider circuit breaker open, skipping");
            return List.of();
        }

        totalCalls.incrementAndGet();
        lastCallAt.set(LocalDateTime.now());
        Instant start = Instant.now();

        try {
            List<DiscoveredCompany> results = new ArrayList<>();

            for (String keyword : query.keywords()) {
                for (String location : query.locations()) {
                    Map<String, Object> params = Map.of(
                            "search_term", keyword,
                            "location", location,
                            "results_wanted", 20,
                            "hours_old", 24
                    );

                    JsonNode response = mcpClient.callTool(MCP_COMMAND, MCP_ARGS, TOOL_NAME, params);
                    results.addAll(parseResponse(response, keyword));
                }
            }

            long elapsed = Duration.between(start, Instant.now()).toMillis();
            totalLatencyMs.addAndGet(elapsed);
            successfulCalls.incrementAndGet();
            companiesDiscovered.addAndGet(results.size());
            consecutiveFailures.set(0);

            log.info("JobSpy discovered {} companies in {}ms", results.size(), elapsed);
            return results;
        } catch (Exception e) {
            long elapsed = Duration.between(start, Instant.now()).toMillis();
            totalLatencyMs.addAndGet(elapsed);
            int failures = consecutiveFailures.incrementAndGet();

            if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
                circuitOpenedAt = Instant.now();
                log.warn("JobSpy circuit breaker opened after {} consecutive failures", failures);
            }

            log.error("JobSpy discovery failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean isHealthy() {
        var config = properties.providers().get("jobspy");
        if (config == null || !config.enabled()) {
            return false;
        }

        if (circuitOpenedAt != null) {
            if (Instant.now().isAfter(circuitOpenedAt.plus(CIRCUIT_BREAKER_COOLDOWN))) {
                // Reset circuit breaker after cooldown
                circuitOpenedAt = null;
                consecutiveFailures.set(0);
                log.info("JobSpy circuit breaker reset after cooldown");
            } else {
                return false;
            }
        }

        return true;
    }

    @Override
    public DiscoveryProviderStats getStats() {
        int total = totalCalls.get();
        Duration avg = total > 0
                ? Duration.ofMillis(totalLatencyMs.get() / total)
                : Duration.ZERO;

        return new DiscoveryProviderStats(
                total,
                successfulCalls.get(),
                companiesDiscovered.get(),
                lastCallAt.get(),
                avg
        );
    }

    private List<DiscoveredCompany> parseResponse(JsonNode response, String keyword) {
        List<DiscoveredCompany> results = new ArrayList<>();

        if (response == null || !response.has("content")) {
            return results;
        }

        JsonNode content = response.get("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                if (item.has("text")) {
                    // Parse text content as job listings
                    results.addAll(parseJobListings(item.get("text").asText(), keyword));
                }
            }
        }

        return results;
    }

    private List<DiscoveredCompany> parseJobListings(String text, String keyword) {
        List<DiscoveredCompany> results = new ArrayList<>();

        // JobSpy returns CSV or structured text - parse company names from output
        String[] lines = text.split("\n");
        for (String line : lines) {
            String[] parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            if (parts.length >= 3) {
                String company = parts[0].replace("\"", "").trim();
                String title = parts.length > 1 ? parts[1].replace("\"", "").trim() : keyword;
                String url = parts.length > 2 ? parts[2].replace("\"", "").trim() : "";

                if (!company.isBlank() && !company.equalsIgnoreCase("company")) {
                    results.add(new DiscoveredCompany(company, title, url, null));
                }
            }
        }

        return results;
    }
}
