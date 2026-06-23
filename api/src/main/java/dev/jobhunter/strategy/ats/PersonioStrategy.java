package dev.jobhunter.strategy.ats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.*;
import java.util.*;

@Slf4j
@Component
public class PersonioStrategy extends AbstractAtsStrategy {

    private static final String DE_BASE_URL = "https://%s.jobs.personio.de";
    private static final String COM_BASE_URL = "https://%s.jobs.personio.com";
    private static final String SEARCH_PATH = "/search.json";
    private static final String APPLY_URL_TEMPLATE = "https://%s.jobs.personio.de/job/%s";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String deBaseUrlTemplate;
    private final String comBaseUrlTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public PersonioStrategy(WebClient webClient, ObjectMapper objectMapper) {
        this(webClient, objectMapper, DE_BASE_URL, COM_BASE_URL);
    }

    // Visible for testing
    PersonioStrategy(WebClient webClient, ObjectMapper objectMapper,
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
    public String name() {
        return "personio";
    }


    @Override
    public FetchResult fetch(FetchContext context) {
        CareerEndpoint endpoint = context.endpoint();
        Instant start = Instant.now();
        String slug = endpoint.getAtsSlug();

        try {
            String responseBody = fetchJobs(slug);

            if (responseBody == null || responseBody.isBlank()) {
                return FetchResult.empty(elapsed(start));
            }

            JsonNode root = objectMapper.readTree(responseBody);

            if (!root.isArray() || root.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            List<RawAggregatorJob> jobs = new ArrayList<>();
            for (JsonNode jobNode : root) {
                RawAggregatorJob job = mapJob(jobNode, slug);
                if (job != null) {
                    jobs.add(job);
                }
            }

            if (jobs.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            log.info("Personio [{}]: extracted {} jobs", slug, jobs.size());
            return FetchResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("Personio [{}]: board not found (404) on both domains", slug);
            return FetchResult.empty(elapsed(start));
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                log.warn("Personio [{}]: rate limited (429) after retries exhausted", slug);
                return FetchResult.rateLimited(elapsed(start));
            }
            log.error("Personio [{}]: HTTP {} - {}", slug, e.getStatusCode(), e.getMessage());
            return FetchResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("Personio [{}]: extraction failed", slug, e);
            return FetchResult.error(e.getMessage(), elapsed(start));
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

    private RawAggregatorJob mapJob(JsonNode node, String slug) {
        try {
            String externalId = String.valueOf(node.path("id").asLong());
            String title = truncate(node.path("name").asText(null), 500);
            String location = truncate(node.path("office").asText(null), 500);
            String applyUrl = String.format(APPLY_URL_TEMPLATE, slug, externalId);
            String rawJson = node.toString();

            LocalDate postedDate = parseDate(node.path("createdAt").asText(null));

            String description = fetchDescription(slug, externalId);

            return new RawAggregatorJob(
                    externalId, title, null, location, description, applyUrl,
                    postedDate, null, null, null, rawJson
            );
        } catch (Exception e) {
            log.warn("Personio: failed to map job node: {}", e.getMessage());
            return null;
        }
    }

    private String fetchDescription(String slug, String jobId) {
        String url = String.format(APPLY_URL_TEMPLATE, slug, jobId);
        try {
            String html = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));
            if (html == null || html.isBlank()) return null;
            return parseDescription(html);
        } catch (Exception e) {
            log.debug("Personio [{}]: failed to fetch description for job {}: {}", slug, jobId, e.getMessage());
            return null;
        }
    }

    String parseDescription(String html) {
        Document doc = Jsoup.parse(html);
        Elements blocks = doc.select(".rich-text-content");
        if (blocks.isEmpty()) {
            // Fallback: try description item containers
            blocks = doc.select(".jb-description-item");
        }
        if (blocks.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (Element block : blocks) {
            String text = block.text().trim();
            if (!text.isBlank()) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(text);
            }
        }
        String result = sb.toString().trim();
        return result.length() > 50 ? result : null;
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
}
