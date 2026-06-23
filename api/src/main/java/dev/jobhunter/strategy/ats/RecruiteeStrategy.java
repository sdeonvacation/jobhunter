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

import java.time.*;
import java.util.*;

@Slf4j
@Component
public class RecruiteeStrategy extends AbstractAtsStrategy {

    private static final String DEFAULT_BASE_URL = "https://%s.recruitee.com";
    private static final String API_PATH = "/api/offers";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baseUrlTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public RecruiteeStrategy(WebClient webClient, ObjectMapper objectMapper) {
        this(webClient, objectMapper, DEFAULT_BASE_URL);
    }

    // Visible for testing
    RecruiteeStrategy(WebClient webClient, ObjectMapper objectMapper, String baseUrlTemplate) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.baseUrlTemplate = baseUrlTemplate;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.RECRUITEE);
    }

    @Override
    public String name() {
        return "recruitee";
    }


    @Override
    public FetchResult fetch(FetchContext context) {
        CareerEndpoint endpoint = context.endpoint();
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
                return FetchResult.empty(elapsed(start));
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode offersNode = root.path("offers");

            if (!offersNode.isArray() || offersNode.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            List<RawAggregatorJob> jobs = new ArrayList<>();
            for (JsonNode offerNode : offersNode) {
                RawAggregatorJob job = mapOffer(offerNode, slug);
                if (job != null) {
                    jobs.add(job);
                }
            }

            if (jobs.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            log.info("Recruitee [{}]: extracted {} jobs", slug, jobs.size());
            return FetchResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("Recruitee [{}]: not found (404)", slug);
            return FetchResult.empty(elapsed(start));
        } catch (WebClientResponseException e) {
            log.error("Recruitee [{}]: HTTP {} - {}", slug, e.getStatusCode(), e.getMessage());
            return FetchResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("Recruitee [{}]: extraction failed", slug, e);
            return FetchResult.error(e.getMessage(), elapsed(start));
        }
    }

    private RawAggregatorJob mapOffer(JsonNode node, String slug) {
        try {
            String externalId = String.valueOf(node.path("id").asLong());
            String title = node.path("title").asText(null);
            String location = buildLocation(node);
            String applyUrl = node.path("careers_url").asText(null);
            String rawJson = node.toString();
            LocalDate postedDate = parseDate(node.path("published_at").asText(null));

            return new RawAggregatorJob(
                    externalId, title, null, location, null, applyUrl,
                    postedDate, null, null, null, rawJson
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
}
