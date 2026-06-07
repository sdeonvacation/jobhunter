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

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Component
public class BambooHrStrategy implements FetchStrategy {

    private static final String DEFAULT_BASE_URL = "https://%s.bamboohr.com";
    private static final String PATH = "/careers/list";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baseUrlTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public BambooHrStrategy(WebClient webClient, ObjectMapper objectMapper) {
        this(webClient, objectMapper, DEFAULT_BASE_URL);
    }

    // Visible for testing
    BambooHrStrategy(WebClient webClient, ObjectMapper objectMapper, String baseUrlTemplate) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.baseUrlTemplate = baseUrlTemplate;
    }

    @Override
    public boolean supports(AtsType type) {
        return type == AtsType.BAMBOOHR;
    }

    @Override
    public String name() {
        return "bamboohr";
    }


    @Override
    public FetchResult fetch(FetchContext context) {
        CareerEndpoint endpoint = context.endpoint();
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
                return FetchResult.empty(elapsed(start));
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode resultNode = root.path("result");

            if (!resultNode.isArray() || resultNode.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            List<RawAggregatorJob> jobs = new ArrayList<>();
            for (JsonNode jobNode : resultNode) {
                RawAggregatorJob job = mapJob(jobNode, slug);
                if (job != null) {
                    jobs.add(job);
                }
            }

            if (jobs.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            log.info("BambooHR [{}]: extracted {} jobs", slug, jobs.size());
            return FetchResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("BambooHR [{}]: not found (404)", slug);
            return FetchResult.empty(elapsed(start));
        } catch (WebClientResponseException e) {
            log.error("BambooHR [{}]: HTTP {} - {}", slug, e.getStatusCode(), e.getMessage());
            return FetchResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("BambooHR [{}]: extraction failed", slug, e);
            return FetchResult.error(e.getMessage(), elapsed(start));
        }
    }

    private RawAggregatorJob mapJob(JsonNode node, String slug) {
        try {
            String externalId = node.path("id").asText(null);
            String title = node.path("jobOpeningName").asText(null);

            String location = buildLocation(node.path("location"));
            String applyUrl = String.format(baseUrlTemplate, slug) + "/careers/" + externalId;
            String rawJson = node.toString();

            return new RawAggregatorJob(
                    externalId, title, null, location, null, applyUrl,
                    null, null, null, null, rawJson
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
