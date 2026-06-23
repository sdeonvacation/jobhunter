package dev.jobhunter.strategy.ats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
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
public class BreezyStrategy extends AbstractAtsStrategy {

    private static final String DEFAULT_BASE_URL = "https://%s.breezy.hr";
    private static final String PATH = "/json";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baseUrlTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public BreezyStrategy(WebClient webClient, ObjectMapper objectMapper) {
        this(webClient, objectMapper, DEFAULT_BASE_URL);
    }

    // Visible for testing
    BreezyStrategy(WebClient webClient, ObjectMapper objectMapper, String baseUrlTemplate) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.baseUrlTemplate = baseUrlTemplate;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.BREEZY);
    }

    @Override
    public String name() {
        return "breezy";
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

            if (!root.isArray() || root.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            List<RawAggregatorJob> jobs = new ArrayList<>();
            for (JsonNode jobNode : root) {
                RawAggregatorJob job = mapJob(jobNode);
                if (job != null) {
                    jobs.add(job);
                }
            }

            if (jobs.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            log.info("Breezy [{}]: extracted {} jobs", slug, jobs.size());
            return FetchResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("Breezy [{}]: not found (404)", slug);
            return FetchResult.empty(elapsed(start));
        } catch (WebClientResponseException e) {
            log.error("Breezy [{}]: HTTP {} - {}", slug, e.getStatusCode(), e.getMessage());
            return FetchResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("Breezy [{}]: extraction failed", slug, e);
            return FetchResult.error(e.getMessage(), elapsed(start));
        }
    }

    private RawAggregatorJob mapJob(JsonNode node) {
        try {
            String externalId = node.path("id").asText(null);
            if (externalId == null) {
                externalId = node.path("_id").asText(null);
            }
            String title = node.path("name").asText(null);

            String location = buildLocation(node.path("location"));
            String applyUrl = node.path("url").asText(null);
            String rawJson = node.toString();

            return new RawAggregatorJob(
                    externalId, title, null, location, null, applyUrl,
                    null, null, null, null, rawJson
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
}
