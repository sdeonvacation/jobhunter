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
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;

@Slf4j
@Component
public class GreenhouseStrategy extends AbstractAtsStrategy {

    private static final String DEFAULT_BASE_URL = "https://boards-api.greenhouse.io";
    private static final String JOBS_PATH = "/v1/boards/%s/jobs?content=true";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @org.springframework.beans.factory.annotation.Autowired
    public GreenhouseStrategy(WebClient webClient, ObjectMapper objectMapper) {
        this(webClient, objectMapper, DEFAULT_BASE_URL);
    }

    // Visible for testing
    GreenhouseStrategy(WebClient webClient, ObjectMapper objectMapper, String baseUrl) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.GREENHOUSE);
    }

    @Override
    public String name() {
        return "greenhouse";
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        CareerEndpoint endpoint = context.endpoint();
        Instant start = Instant.now();
        String slug = endpoint.getAtsSlug();

        try {
            String url = baseUrl + String.format(JOBS_PATH, slug);
            String responseBody = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(45));

            if (responseBody == null || responseBody.isBlank()) {
                return FetchResult.empty(elapsed(start));
            }

            if (responseBody.length() > 10_000_000) {
                log.warn("Greenhouse [{}]: large response ({} MB)", slug, responseBody.length() / 1_048_576);
            }

            JsonNode root = objectMapper.readTree(responseBody);

            // Some Greenhouse slugs return 200 OK with error JSON body instead of HTTP 404
            if (root.has("status") && root.path("status").isInt()) {
                int status = root.path("status").asInt();
                if (status >= 400) {
                    log.warn("Greenhouse [{}]: API returned error in body (status: {})", slug, status);
                    return FetchResult.empty(elapsed(start));
                }
            }

            JsonNode jobsNode = root.path("jobs");

            if (!jobsNode.isArray() || jobsNode.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            List<RawAggregatorJob> jobs = new ArrayList<>();
            for (JsonNode jobNode : jobsNode) {
                RawAggregatorJob job = mapJob(jobNode);
                if (job != null) {
                    jobs.add(job);
                }
            }

            if (jobs.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            log.info("Greenhouse [{}]: extracted {} jobs", slug, jobs.size());
            return FetchResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("Greenhouse [{}]: board not found (404)", slug);
            return FetchResult.error("ATS board not found (404)", elapsed(start));
        } catch (WebClientResponseException e) {
            log.error("Greenhouse [{}]: HTTP {} - {}", slug, e.getStatusCode(), e.getMessage());
            return FetchResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("Greenhouse [{}]: extraction failed - {} : {}", slug, e.getClass().getSimpleName(), e.getMessage(), e);
            return FetchResult.error(e.getClass().getSimpleName() + ": " + e.getMessage(), elapsed(start));
        }
    }


    private RawAggregatorJob mapJob(JsonNode node) {
        try {
            String externalId = String.valueOf(node.path("id").asLong());
            String title = truncate(node.path("title").asText(null), 500);
            String location = truncate(node.path("location").path("name").asText(null), 500);
            String contentHtml = node.path("content").asText("");
            String description = stripHtml(contentHtml);
            String applyUrl = node.path("absolute_url").asText(null);
            String rawJson = node.toString();

            // first_published is the real post date; updated_at is last-modified and makes
            // long-open roles look freshly posted (59% of rows disagree).
            LocalDate postedDate = parseDate(node.path("first_published").asText(null));

            return new RawAggregatorJob(
                    externalId, title, null, location, description, applyUrl,
                    postedDate, null, null, null, rawJson
            );
        } catch (Exception e) {
            log.warn("Greenhouse: failed to map job node: {}", e.getMessage());
            return null;
        }
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(dateStr).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }
}
