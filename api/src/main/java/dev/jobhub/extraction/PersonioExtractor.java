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
public class PersonioExtractor implements JobExtractor {

    private static final String DE_BASE_URL = "https://%s.jobs.personio.de";
    private static final String COM_BASE_URL = "https://%s.jobs.personio.com";
    private static final String SEARCH_PATH = "/search.json";
    private static final String APPLY_URL_TEMPLATE = "https://%s.jobs.personio.de/job/%s";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String deBaseUrlTemplate;
    private final String comBaseUrlTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public PersonioExtractor(WebClient webClient, ObjectMapper objectMapper) {
        this(webClient, objectMapper, DE_BASE_URL, COM_BASE_URL);
    }

    // Visible for testing
    PersonioExtractor(WebClient webClient, ObjectMapper objectMapper,
                      String deBaseUrlTemplate, String comBaseUrlTemplate) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.deBaseUrlTemplate = deBaseUrlTemplate;
        this.comBaseUrlTemplate = comBaseUrlTemplate;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.PERSONIO);
    }

    @Override
    public boolean canExtract(CareerEndpoint endpoint) {
        return endpoint.getAtsType() == AtsType.PERSONIO
                && endpoint.getAtsSlug() != null
                && !endpoint.getAtsSlug().isBlank();
    }

    @Override
    public ExtractionResult extract(CareerEndpoint endpoint) {
        Instant start = Instant.now();
        String slug = endpoint.getAtsSlug();

        try {
            String responseBody = fetchJobs(slug);

            if (responseBody == null || responseBody.isBlank()) {
                return ExtractionResult.empty(elapsed(start));
            }

            JsonNode root = objectMapper.readTree(responseBody);

            if (!root.isArray() || root.isEmpty()) {
                return ExtractionResult.empty(elapsed(start));
            }

            List<RawJobData> jobs = new ArrayList<>();
            for (JsonNode jobNode : root) {
                RawJobData job = mapJob(jobNode, slug);
                if (job != null) {
                    jobs.add(job);
                }
            }

            if (jobs.isEmpty()) {
                return ExtractionResult.empty(elapsed(start));
            }

            log.info("Personio [{}]: extracted {} jobs", slug, jobs.size());
            return ExtractionResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("Personio [{}]: board not found (404) on both domains", slug);
            return ExtractionResult.empty(elapsed(start));
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                log.warn("Personio [{}]: rate limited (429) after retries exhausted", slug);
                return ExtractionResult.rateLimited(elapsed(start));
            }
            log.error("Personio [{}]: HTTP {} - {}", slug, e.getStatusCode(), e.getMessage());
            return ExtractionResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("Personio [{}]: extraction failed", slug, e);
            return ExtractionResult.error(e.getMessage(), elapsed(start));
        }
    }

    private String fetchJobs(String slug) {
        String deUrl = String.format(deBaseUrlTemplate, slug) + SEARCH_PATH;
        try {
            return webClient.get()
                    .uri(deUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(45));
        } catch (WebClientResponseException.NotFound e) {
            // .de domain returned 404, try .com
            log.debug("Personio [{}]: .de domain not found, trying .com", slug);
            String comUrl = String.format(comBaseUrlTemplate, slug) + SEARCH_PATH;
            return webClient.get()
                    .uri(comUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(45));
        }
    }

    private RawJobData mapJob(JsonNode node, String slug) {
        try {
            String externalId = String.valueOf(node.path("id").asLong());
            String title = truncate(node.path("name").asText(null), 500);
            String location = truncate(node.path("office").asText(null), 500);
            String applyUrl = String.format(APPLY_URL_TEMPLATE, slug, externalId);
            String rawJson = node.toString();

            LocalDate postedDate = parseDate(node.path("createdAt").asText(null));

            return new RawJobData(
                    externalId, title, location, null, applyUrl,
                    rawJson, null, null, null, postedDate
            );
        } catch (Exception e) {
            log.warn("Personio: failed to map job node: {}", e.getMessage());
            return null;
        }
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            try {
                return ZonedDateTime.parse(dateStr).toLocalDate();
            } catch (Exception e2) {
                try {
                    return OffsetDateTime.parse(dateStr).toLocalDate();
                } catch (Exception e3) {
                    return null;
                }
            }
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
