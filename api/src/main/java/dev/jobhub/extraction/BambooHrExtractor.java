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
public class BambooHrExtractor implements JobExtractor {

    private static final String DEFAULT_BASE_URL = "https://%s.bamboohr.com";
    private static final String PATH = "/careers/list";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baseUrlTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public BambooHrExtractor(WebClient webClient, ObjectMapper objectMapper) {
        this(webClient, objectMapper, DEFAULT_BASE_URL);
    }

    // Visible for testing
    BambooHrExtractor(WebClient webClient, ObjectMapper objectMapper, String baseUrlTemplate) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.baseUrlTemplate = baseUrlTemplate;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.BAMBOOHR);
    }

    @Override
    public boolean canExtract(CareerEndpoint endpoint) {
        return endpoint.getAtsType() == AtsType.BAMBOOHR
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
            JsonNode resultNode = root.path("result");

            if (!resultNode.isArray() || resultNode.isEmpty()) {
                return ExtractionResult.empty(elapsed(start));
            }

            List<RawJobData> jobs = new ArrayList<>();
            for (JsonNode jobNode : resultNode) {
                RawJobData job = mapJob(jobNode, slug);
                if (job != null) {
                    jobs.add(job);
                }
            }

            if (jobs.isEmpty()) {
                return ExtractionResult.empty(elapsed(start));
            }

            log.info("BambooHR [{}]: extracted {} jobs", slug, jobs.size());
            return ExtractionResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("BambooHR [{}]: not found (404)", slug);
            return ExtractionResult.empty(elapsed(start));
        } catch (WebClientResponseException e) {
            log.error("BambooHR [{}]: HTTP {} - {}", slug, e.getStatusCode(), e.getMessage());
            return ExtractionResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("BambooHR [{}]: extraction failed", slug, e);
            return ExtractionResult.error(e.getMessage(), elapsed(start));
        }
    }

    private RawJobData mapJob(JsonNode node, String slug) {
        try {
            String externalId = node.path("id").asText(null);
            String title = node.path("jobOpeningName").asText(null);

            String location = buildLocation(node.path("location"));
            String applyUrl = String.format(baseUrlTemplate, slug) + "/careers/" + externalId;
            String rawJson = node.toString();

            return new RawJobData(
                    externalId, title, location, null, applyUrl,
                    rawJson, null, null, null, null
            );
        } catch (Exception e) {
            log.warn("BambooHR: failed to map job node: {}", e.getMessage());
            return null;
        }
    }

    private String buildLocation(JsonNode locationNode) {
        if (locationNode.isMissingNode() || locationNode.isNull()) {
            return null;
        }
        String city = locationNode.path("city").asText("");
        String country = locationNode.path("country").asText("");

        if (city.isBlank() && country.isBlank()) {
            return null;
        }
        if (city.isBlank()) {
            return country;
        }
        if (country.isBlank()) {
            return city;
        }
        return city + ", " + country;
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
