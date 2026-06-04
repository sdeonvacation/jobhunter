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
public class RecruiteeExtractor implements JobExtractor {

    private static final String DEFAULT_BASE_URL = "https://%s.recruitee.com";
    private static final String API_PATH = "/api/offers";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baseUrlTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public RecruiteeExtractor(WebClient webClient, ObjectMapper objectMapper) {
        this(webClient, objectMapper, DEFAULT_BASE_URL);
    }

    // Visible for testing
    RecruiteeExtractor(WebClient webClient, ObjectMapper objectMapper, String baseUrlTemplate) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.baseUrlTemplate = baseUrlTemplate;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.RECRUITEE);
    }

    @Override
    public boolean canExtract(CareerEndpoint endpoint) {
        return endpoint.getAtsType() == AtsType.RECRUITEE
                && endpoint.getAtsSlug() != null
                && !endpoint.getAtsSlug().isBlank();
    }

    @Override
    public ExtractionResult extract(CareerEndpoint endpoint) {
        Instant start = Instant.now();
        String slug = endpoint.getAtsSlug();

        try {
            String url = String.format(baseUrlTemplate, slug) + API_PATH;
            String responseBody = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(45));

            if (responseBody == null || responseBody.isBlank()) {
                return ExtractionResult.empty(elapsed(start));
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode offersNode = root.path("offers");

            if (!offersNode.isArray() || offersNode.isEmpty()) {
                return ExtractionResult.empty(elapsed(start));
            }

            List<RawJobData> jobs = new ArrayList<>();
            for (JsonNode offerNode : offersNode) {
                RawJobData job = mapOffer(offerNode, slug);
                if (job != null) {
                    jobs.add(job);
                }
            }

            if (jobs.isEmpty()) {
                return ExtractionResult.empty(elapsed(start));
            }

            log.info("Recruitee [{}]: extracted {} jobs", slug, jobs.size());
            return ExtractionResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("Recruitee [{}]: not found (404)", slug);
            return ExtractionResult.empty(elapsed(start));
        } catch (WebClientResponseException e) {
            log.error("Recruitee [{}]: HTTP {} - {}", slug, e.getStatusCode(), e.getMessage());
            return ExtractionResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("Recruitee [{}]: extraction failed", slug, e);
            return ExtractionResult.error(e.getMessage(), elapsed(start));
        }
    }

    private RawJobData mapOffer(JsonNode node, String slug) {
        try {
            String externalId = String.valueOf(node.path("id").asLong());
            String title = node.path("title").asText(null);
            String location = buildLocation(node);
            String applyUrl = node.path("careers_url").asText(null);
            String rawJson = node.toString();
            LocalDate postedDate = parseDate(node.path("published_at").asText(null));

            return new RawJobData(
                    externalId, title, location, null, applyUrl,
                    rawJson, null, null, null, postedDate
            );
        } catch (Exception e) {
            log.warn("Recruitee: failed to map offer node: {}", e.getMessage());
            return null;
        }
    }

    private String buildLocation(JsonNode node) {
        String city = node.path("city").asText("");
        String country = node.path("country").asText("");

        if (!city.isBlank() && !country.isBlank()) {
            return city + ", " + country;
        } else if (!city.isBlank()) {
            return city;
        } else if (!country.isBlank()) {
            return country;
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
                return LocalDate.parse(dateStr.substring(0, 10));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
