package dev.jobhunter.strategy.ats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches jobs from Pinpoint ATS.
 * API endpoint: {baseUrl}/postings.json → { "data": [...] }
 */
@Slf4j
@Component
public class PinpointStrategy implements FetchStrategy {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public PinpointStrategy(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(AtsType type) {
        return type == AtsType.PINPOINT;
    }

    @Override
    public String name() {
        return "pinpoint";
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        CareerEndpoint endpoint = context.endpoint();
        Instant start = Instant.now();

        String baseUrl = endpoint.getUrl();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        // Pinpoint uses slug-based subdomain: {slug}.pinpointhq.com
        // Or custom domain like careers.company.com
        String apiUrl = baseUrl + "/postings.json";

        try {
            String responseBody = webClient.get()
                    .uri(apiUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(45));

            if (responseBody == null || responseBody.isBlank()) {
                return FetchResult.empty(elapsed(start));
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataNode = root.path("data");

            if (!dataNode.isArray() || dataNode.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            List<RawAggregatorJob> jobs = new ArrayList<>();
            for (JsonNode node : dataNode) {
                RawAggregatorJob job = mapJob(node);
                if (job != null) {
                    jobs.add(job);
                }
            }

            if (jobs.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            log.info("Pinpoint [{}]: extracted {} jobs", baseUrl, jobs.size());
            return FetchResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("Pinpoint [{}]: not found (404)", apiUrl);
            return FetchResult.empty(elapsed(start));
        } catch (WebClientResponseException e) {
            log.error("Pinpoint [{}]: HTTP {} - {}", apiUrl, e.getStatusCode(), e.getMessage());
            return FetchResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("Pinpoint [{}]: extraction failed", apiUrl, e);
            return FetchResult.error(e.getMessage(), elapsed(start));
        }
    }

    private RawAggregatorJob mapJob(JsonNode node) {
        try {
            String externalId = node.path("id").asText(null);
            String title = node.path("title").asText(null);
            if (title == null || title.isBlank()) return null;

            // Location: { "city": "...", "name": "..." }
            JsonNode locationNode = node.path("location");
            String location = locationNode.path("city").asText(
                    locationNode.path("name").asText(null));

            // Description is HTML
            String description = stripHtml(node.path("description").asText(""));

            // Apply URL
            String applyUrl = node.path("url").asText(null);

            // Compensation
            BigDecimal salaryMin = parseBigDecimal(node.path("compensation_minimum"));
            BigDecimal salaryMax = parseBigDecimal(node.path("compensation_maximum"));
            String salaryCurrency = node.path("compensation_currency").asText(null);

            String rawJson = node.toString();

            return new RawAggregatorJob(
                    externalId, title, null, location, description, applyUrl,
                    null, salaryMin, salaryMax, salaryCurrency, rawJson
            );
        } catch (Exception e) {
            log.debug("Pinpoint: failed to map job node: {}", e.getMessage());
            return null;
        }
    }

    private BigDecimal parseBigDecimal(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        try {
            return node.decimalValue();
        } catch (Exception e) {
            return null;
        }
    }

    private String stripHtml(String html) {
        if (html == null || html.isBlank()) return "";
        return html
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#39;", "'")
                .replaceAll("<!--.*?-->", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
