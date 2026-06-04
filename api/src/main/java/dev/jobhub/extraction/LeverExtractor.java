package dev.jobhub.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.model.enums.AtsType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.*;
import java.util.*;

@Slf4j
@Component
public class LeverExtractor implements JobExtractor {

    private static final String API_URL = "https://api.lever.co/v0/postings/%s?mode=json";
    private static final String API_URL_EU = "https://api.eu.lever.co/v0/postings/%s?mode=json";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public LeverExtractor(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.LEVER, AtsType.LEVER_EU);
    }

    @Override
    public ExtractionResult extract(CareerEndpoint endpoint) {
        Instant start = Instant.now();
        String slug = endpoint.getAtsSlug();

        try {
            String urlTemplate = endpoint.getAtsType() == AtsType.LEVER_EU ? API_URL_EU : API_URL;
            String url = String.format(urlTemplate, slug);

            String responseBody = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (responseBody == null || responseBody.isBlank()) {
                return ExtractionResult.empty(elapsed(start));
            }

            JsonNode root = objectMapper.readTree(responseBody);

            if (!root.isArray() || root.isEmpty()) {
                return ExtractionResult.empty(elapsed(start));
            }

            List<RawJobData> jobs = new ArrayList<>();
            for (JsonNode jobNode : root) {
                RawJobData job = mapJob(jobNode);
                if (job != null) {
                    jobs.add(job);
                }
            }

            if (jobs.isEmpty()) {
                return ExtractionResult.empty(elapsed(start));
            }

            log.info("Lever [{}]: extracted {} jobs", slug, jobs.size());
            return ExtractionResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("Lever [{}]: not found (404)", slug);
            return ExtractionResult.empty(elapsed(start));
        } catch (WebClientResponseException e) {
            log.error("Lever [{}]: HTTP {} - {}", slug, e.getStatusCode(), e.getMessage());
            return ExtractionResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("Lever [{}]: extraction failed", slug, e);
            return ExtractionResult.error(e.getMessage(), elapsed(start));
        }
    }

    @Override
    public boolean canExtract(CareerEndpoint endpoint) {
        return (endpoint.getAtsType() == AtsType.LEVER || endpoint.getAtsType() == AtsType.LEVER_EU)
                && endpoint.getAtsSlug() != null
                && !endpoint.getAtsSlug().isBlank();
    }

    private RawJobData mapJob(JsonNode node) {
        try {
            String externalId = node.path("id").asText(null);
            String title = truncate(node.path("text").asText(null), 500);
            String location = truncate(node.path("categories").path("location").asText(null), 500);
            String description = node.path("descriptionPlain").asText("");
            String applyUrl = node.path("hostedUrl").asText(null);
            String rawJson = node.toString();

            LocalDate postedDate = parseEpochMillis(node.path("createdAt").asLong(0));

            return new RawJobData(
                    externalId, title, location, description, applyUrl,
                    rawJson, null, null, null, postedDate
            );
        } catch (Exception e) {
            log.warn("Lever: failed to map job node: {}", e.getMessage());
            return null;
        }
    }

    private LocalDate parseEpochMillis(long millis) {
        if (millis <= 0) {
            return null;
        }
        return Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
