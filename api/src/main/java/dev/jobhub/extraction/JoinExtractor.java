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
public class JoinExtractor implements JobExtractor {

    private static final String DEFAULT_BASE_URL = "https://api.join.com";
    private static final String PATH_TEMPLATE = "/v1/companies/%s/jobs";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @org.springframework.beans.factory.annotation.Autowired
    public JoinExtractor(WebClient webClient, ObjectMapper objectMapper) {
        this(webClient, objectMapper, DEFAULT_BASE_URL);
    }

    // Visible for testing
    JoinExtractor(WebClient webClient, ObjectMapper objectMapper, String baseUrl) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.JOIN);
    }

    @Override
    public boolean canExtract(CareerEndpoint endpoint) {
        return endpoint.getAtsType() == AtsType.JOIN
                && endpoint.getAtsSlug() != null
                && !endpoint.getAtsSlug().isBlank();
    }

    @Override
    public ExtractionResult extract(CareerEndpoint endpoint) {
        Instant start = Instant.now();
        String slug = endpoint.getAtsSlug();

        try {
            String url = baseUrl + String.format(PATH_TEMPLATE, slug);
            String responseBody = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(45));

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

            log.info("Join [{}]: extracted {} jobs", slug, jobs.size());
            return ExtractionResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("Join [{}]: company not found (404)", slug);
            return ExtractionResult.empty(elapsed(start));
        } catch (WebClientResponseException e) {
            log.error("Join [{}]: HTTP {} - {}", slug, e.getStatusCode(), e.getMessage());
            return ExtractionResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("Join [{}]: extraction failed", slug, e);
            return ExtractionResult.error(e.getMessage(), elapsed(start));
        }
    }

    private RawJobData mapJob(JsonNode node) {
        try {
            String externalId = node.path("id").asText(null);
            String title = node.path("title").asText(null);
            String location = buildLocation(node);
            String applyUrl = node.path("jobUrl").asText(null);
            String rawJson = node.toString();
            LocalDate postedDate = parseDate(node.path("createdAt").asText(null));

            return new RawJobData(
                    externalId, title, location, null, applyUrl,
                    rawJson, null, null, null, postedDate
            );
        } catch (Exception e) {
            log.warn("Join: failed to map job node: {}", e.getMessage());
            return null;
        }
    }

    private String buildLocation(JsonNode node) {
        String city = node.path("city").asText(null);
        String countryCode = node.path("countryCode").asText(null);

        if (city != null && !city.isBlank() && countryCode != null && !countryCode.isBlank()) {
            return city + ", " + countryCode;
        } else if (city != null && !city.isBlank()) {
            return city;
        } else if (countryCode != null && !countryCode.isBlank()) {
            return countryCode;
        }
        return null;
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(dateStr).toLocalDate();
        } catch (Exception e) {
            try {
                return LocalDate.parse(dateStr);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
