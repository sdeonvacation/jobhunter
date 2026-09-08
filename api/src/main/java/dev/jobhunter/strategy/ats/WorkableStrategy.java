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
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Component
public class WorkableStrategy extends AbstractAtsStrategy {

    private static final String DEFAULT_BASE_URL = "https://apply.workable.com";
    private static final String LIST_PATH_TEMPLATE = "/api/v1/widget/accounts/%s?details=true";
    private static final String APPLY_URL_TEMPLATE = "https://apply.workable.com/%s/j/%s/";
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @org.springframework.beans.factory.annotation.Autowired
    public WorkableStrategy(WebClient webClient, ObjectMapper objectMapper) {
        this(webClient, objectMapper, DEFAULT_BASE_URL);
    }

    // Visible for testing
    WorkableStrategy(WebClient webClient, ObjectMapper objectMapper, String baseUrl) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.WORKABLE);
    }

    @Override
    public String name() {
        return "workable";
    }


    @Override
    public FetchResult fetch(FetchContext context) {
        CareerEndpoint endpoint = context.endpoint();
        String slug = endpoint.getAtsSlug();
        Instant start = Instant.now();

        try {
            String url = baseUrl + String.format(LIST_PATH_TEMPLATE, slug);

            String response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(45));

            if (response == null || response.isBlank()) {
                log.info("Workable [{}]: empty response", slug);
                return FetchResult.empty(elapsed(start));
            }

            JsonNode root = objectMapper.readTree(response);
            JsonNode jobs = root.path("jobs");

            if (!jobs.isArray() || jobs.isEmpty()) {
                log.info("Workable [{}]: no jobs found", slug);
                return FetchResult.empty(elapsed(start));
            }

            List<RawAggregatorJob> allJobs = new ArrayList<>();
            for (JsonNode node : jobs) {
                RawAggregatorJob job = mapJob(node, slug);
                if (job != null) {
                    allJobs.add(job);
                }
            }

            if (allJobs.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            // Workable returns one entry per country for the same posting (same shortcode).
            // Merge them into a single job with the combined country list so the location
            // filter sees the full set (e.g. a remote role listed for BE+PL+UK+NL+DE+FR
            // resolves to DE → visa-exempt → kept).
            List<RawAggregatorJob> merged = mergeMultiCountryVariants(allJobs);

            log.info("Workable [{}]: extracted {} jobs ({} after merging multi-country variants)",
                    slug, allJobs.size(), merged.size());
            return FetchResult.success(merged, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("Workable [{}]: account not found (404)", slug);
            return FetchResult.empty(elapsed(start));
        } catch (Exception e) {
            log.error("Workable [{}]: extraction failed: {}", slug, e.getMessage());
            return FetchResult.error(e.getMessage(), elapsed(start));
        }
    }

    private RawAggregatorJob mapJob(JsonNode node, String slug) {
        try {
            String shortcode = node.path("shortcode").asText(null);
            if (shortcode == null || shortcode.isBlank()) {
                return null;
            }

            String title = truncate(node.path("title").asText(null), 500);
            String location = truncate(buildLocation(node), 500);
            String applyUrl = String.format(APPLY_URL_TEMPLATE, slug, shortcode);

            // Parse created_at date
            String createdAt = node.path("created_at").asText(null);
            LocalDate postedDate = parseDate(createdAt);

            String descriptionHtml = node.path("description").asText(null);
            String description = descriptionHtml != null
                    ? HTML_TAG_PATTERN.matcher(descriptionHtml).replaceAll("").strip()
                    : null;

            String rawJson = node.toString();

            return new RawAggregatorJob(
                    shortcode, title, null, location, description, applyUrl,
                    postedDate, null, null, null, rawJson
            );
        } catch (Exception e) {
            log.warn("Workable: failed to map job: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Workable returns one entry per country for the same posting (same shortcode).
     * Merge variants into a single job whose location lists all countries, e.g.
     * "Belgium, Poland, United Kingdom, Netherlands, Germany, France". This lets the
     * location filter resolve the full set (Germany present → visa-exempt → kept).
     */
    private List<RawAggregatorJob> mergeMultiCountryVariants(List<RawAggregatorJob> jobs) {
        Map<String, RawAggregatorJob> byShortcode = new LinkedHashMap<>();
        for (RawAggregatorJob job : jobs) {
            RawAggregatorJob existing = byShortcode.get(job.externalId());
            if (existing == null) {
                byShortcode.put(job.externalId(), job);
            } else {
                byShortcode.put(job.externalId(), mergeLocations(existing, job));
            }
        }
        return new ArrayList<>(byShortcode.values());
    }

    private RawAggregatorJob mergeLocations(RawAggregatorJob first, RawAggregatorJob second) {
        String mergedLocation = mergeLocationStrings(first.location(), second.location());
        return new RawAggregatorJob(
                first.externalId(), first.title(), first.companyName(), mergedLocation,
                first.description() != null ? first.description() : second.description(),
                first.applyUrl(), first.postedDate(),
                first.salaryMin(), first.salaryMax(), first.salaryCurrency(), first.rawJson());
    }

    private String mergeLocationStrings(String a, String b) {
        if (a == null || a.isBlank()) return b;
        if (b == null || b.isBlank()) return a;
        // Avoid duplicates (same city/country appearing in multiple variants)
        Set<String> parts = new LinkedHashSet<>();
        for (String part : (a + ", " + b).split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) parts.add(trimmed);
        }
        return String.join(", ", parts);
    }

    private String buildLocation(JsonNode node) {
        String city = node.path("city").asText("");
        String state = node.path("state").asText("");
        String country = node.path("country").asText("");

        StringBuilder sb = new StringBuilder();
        if (!city.isBlank()) sb.append(city);
        if (!state.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(state);
        }
        if (!country.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(country);
        }
        return sb.toString();
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            // Workable uses ISO format like "2024-01-15" or "2024-01-15T10:00:00Z"
            return LocalDate.parse(dateStr.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return null;
        }
    }
}
