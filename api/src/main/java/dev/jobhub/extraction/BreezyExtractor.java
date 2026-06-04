package dev.jobhub.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.model.enums.AtsType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Component
public class BreezyExtractor implements JobExtractor {

    private static final String DEFAULT_BASE_URL = "https://%s.breezy.hr";
    private static final String PATH = "/json";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baseUrlTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public BreezyExtractor(WebClient webClient, ObjectMapper objectMapper) {
        this(webClient, objectMapper, DEFAULT_BASE_URL);
    }

    // Visible for testing
    BreezyExtractor(WebClient webClient, ObjectMapper objectMapper, String baseUrlTemplate) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.baseUrlTemplate = baseUrlTemplate;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.BREEZY);
    }

    @Override
    public boolean canExtract(CareerEndpoint endpoint) {
        return endpoint.getAtsType() == AtsType.BREEZY
                && endpoint.getAtsSlug() != null
                && !endpoint.getAtsSlug().isBlank();
    }

    @Override
    public ExtractionResult extract(CareerEndpoint endpoint) {
        Instant start = Instant.now();
        String slug = endpoint.getAtsSlug();

        try {
            String url = String.format(baseUrlTemplate, slug) + PATH;
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

            log.info("Breezy [{}]: extracted {} jobs", slug, jobs.size());
            return ExtractionResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("Breezy [{}]: not found (404)", slug);
            return ExtractionResult.empty(elapsed(start));
        } catch (WebClientResponseException e) {
            log.error("Breezy [{}]: HTTP {} - {}", slug, e.getStatusCode(), e.getMessage());
            return ExtractionResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("Breezy [{}]: extraction failed", slug, e);
            return ExtractionResult.error(e.getMessage(), elapsed(start));
        }
    }

    private RawJobData mapJob(JsonNode node) {
        try {
            String externalId = node.path("id").asText(null);
            if (externalId == null) {
                externalId = node.path("_id").asText(null);
            }
            String title = node.path("name").asText(null);

            String location = buildLocation(node.path("location"));
            String applyUrl = node.path("url").asText(null);
            String rawJson = node.toString();

            return new RawJobData(
                    externalId, title, location, null, applyUrl,
                    rawJson, null, null, null, null
            );
        } catch (Exception e) {
            log.warn("Breezy: failed to map job node: {}", e.getMessage());
            return null;
        }
    }

    private String buildLocation(JsonNode locationNode) {
        if (locationNode.isMissingNode() || locationNode.isNull()) {
            return null;
        }
        // Breezy location can be: { "name": "Germany", "country": { "name": "Germany" }, "city": "Berlin" }
        // or simpler: { "city": "Berlin", "country": "Germany" }
        String city = locationNode.path("city").asText("");
        if (city.isBlank()) {
            city = locationNode.path("name").asText("");
        }
        String country = "";
        JsonNode countryNode = locationNode.path("country");
        if (countryNode.isObject()) {
            country = countryNode.path("name").asText("");
        } else {
            country = countryNode.asText("");
        }

        if (city.isBlank() && country.isBlank()) {
            return null;
        }
        if (city.isBlank()) return country;
        if (country.isBlank()) return city;
        if (city.equalsIgnoreCase(country)) return city;
        return city + ", " + country;
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
