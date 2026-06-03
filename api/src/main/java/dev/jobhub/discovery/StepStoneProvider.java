package dev.jobhub.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobhub.mcp.McpSidecarClient;
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
 * Discovery provider that calls mcp-stepstone sidecar via MCP JSON-RPC over stdio.
 * Uses postal codes from config to discover companies posting on StepStone.
 * Implements circuit breaker: 3 consecutive failures → disable for 1 hour.
 */
@Slf4j
@Component
public class StepStoneProvider implements DiscoveryProvider {

    private static final int CIRCUIT_BREAKER_THRESHOLD = 3;
    private static final Duration CIRCUIT_BREAKER_COOLDOWN = Duration.ofHours(1);
    private static final String MCP_COMMAND = "npx";
    private static final List<String> MCP_ARGS = List.of("-y", "mcp-stepstone");
    private static final String TOOL_NAME = "search_jobs";

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

    public StepStoneProvider(McpSidecarClient mcpClient, DiscoveryProperties properties) {
        this.mcpClient = mcpClient;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "stepstone";
    }

    @Override
    public List<DiscoveredCompany> discover(DiscoveryQuery query) {
        if (!isHealthy()) {
            log.debug("StepStone provider circuit breaker open, skipping");
            return List.of();
        }

        var config = properties.providers().get("stepstone");
        List<String> postalCodes = config != null ? config.postalCodes() : List.of();

        totalCalls.incrementAndGet();
        lastCallAt.set(LocalDateTime.now());
        Instant start = Instant.now();

        try {
            List<DiscoveredCompany> results = new ArrayList<>();

            for (String keyword : query.keywords()) {
                for (String postalCode : postalCodes) {
                    Map<String, Object> params = Map.of(
                            "keyword", keyword,
                            "postal_code", postalCode,
                            "radius_km", 25,
                            "page_size", 25
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

            log.info("StepStone discovered {} companies in {}ms", results.size(), elapsed);
            return results;
        } catch (Exception e) {
            long elapsed = Duration.between(start, Instant.now()).toMillis();
            totalLatencyMs.addAndGet(elapsed);
            int failures = consecutiveFailures.incrementAndGet();

            if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
                circuitOpenedAt = Instant.now();
                log.warn("StepStone circuit breaker opened after {} consecutive failures", failures);
            }

            log.error("StepStone discovery failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean isHealthy() {
        var config = properties.providers().get("stepstone");
        if (config == null || !config.enabled()) {
            return false;
        }

        if (circuitOpenedAt != null) {
            if (Instant.now().isAfter(circuitOpenedAt.plus(CIRCUIT_BREAKER_COOLDOWN))) {
                circuitOpenedAt = null;
                consecutiveFailures.set(0);
                log.info("StepStone circuit breaker reset after cooldown");
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
                    results.addAll(parseJobListings(item.get("text").asText(), keyword));
                }
            }
        }

        return results;
    }

    private List<DiscoveredCompany> parseJobListings(String text, String keyword) {
        List<DiscoveredCompany> results = new ArrayList<>();

        String[] lines = text.split("\n");
        for (String line : lines) {
            // StepStone MCP returns structured text with company|title|url format
            String[] parts = line.split("\\|");
            if (parts.length >= 2) {
                String company = parts[0].trim();
                String title = parts[1].trim();
                String url = parts.length > 2 ? parts[2].trim() : "";

                if (!company.isBlank()) {
                    results.add(new DiscoveredCompany(company, title, url, null));
                }
            }
        }

        return results;
    }
}
