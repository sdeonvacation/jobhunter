package dev.jobhunter.strategy.ats;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.*;
import java.util.*;

@Slf4j
@Component
public class JoinStrategy extends AbstractAtsStrategy {

    private static final String DEFAULT_BASE_URL = "https://join.com";
    private static final String LIST_PATH_TEMPLATE = "/companies/%s";
    private static final String PAGE_QUERY_TEMPLATE = "?page=%d";
    private static final int MAX_PAGES = 25;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @org.springframework.beans.factory.annotation.Autowired
    public JoinStrategy(WebClient webClient, ObjectMapper objectMapper) {
        this(webClient, objectMapper, DEFAULT_BASE_URL);
    }

    // Visible for testing
    JoinStrategy(WebClient webClient, ObjectMapper objectMapper, String baseUrl) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.JOIN);
    }

    @Override
    public String name() {
        return "join";
    }


    @Override
    public FetchResult fetch(FetchContext context) {
        CareerEndpoint endpoint = context.endpoint();
        Instant start = Instant.now();
        String slug = endpoint.getAtsSlug();

        try {
            JsonNode firstPage = fetchPage(slug, 1);
            if (firstPage == null) {
                log.warn("Join [{}]: no __NEXT_DATA__ payload on company page", slug);
                return FetchResult.empty(elapsed(start));
            }

            JsonNode jobsNode = jobsNode(firstPage);
            if (jobsNode == null || !jobsNode.path("items").isArray() || jobsNode.path("items").isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            int pageCount = jobsNode.path("pagination").path("pageCount").asInt(1);
            int lastPage = Math.min(Math.max(pageCount, 1), MAX_PAGES);

            List<RawAggregatorJob> jobs = new ArrayList<>();
            Set<String> seenExternalIds = new HashSet<>();
            collectJobs(jobsNode.path("items"), slug, jobs, seenExternalIds);

            for (int page = 2; page <= lastPage; page++) {
                JsonNode pageRoot;
                try {
                    pageRoot = fetchPage(slug, page);
                } catch (Exception e) {
                    // A failing follow-up page must not discard jobs already collected from page 1
                    log.warn("Join [{}]: page {} unavailable: {}", slug, page, e.getMessage());
                    break;
                }
                JsonNode pageJobs = jobsNode(pageRoot);
                if (pageJobs == null || !pageJobs.path("items").isArray() || pageJobs.path("items").isEmpty()) {
                    break;
                }
                collectJobs(pageJobs.path("items"), slug, jobs, seenExternalIds);
            }

            if (jobs.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            log.info("Join [{}]: extracted {} jobs", slug, jobs.size());
            return FetchResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("Join [{}]: company not found (404)", slug);
            return FetchResult.empty(elapsed(start));
        } catch (WebClientResponseException e) {
            log.error("Join [{}]: HTTP {} - {}", slug, e.getStatusCode(), e.getMessage());
            return FetchResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("Join [{}]: extraction failed", slug, e);
            return FetchResult.error(e.getMessage(), elapsed(start));
        }
    }

    /**
     * Fetches a public company page and parses its embedded Next.js {@code __NEXT_DATA__} payload.
     * Returns {@code null} when the page carries no such script.
     */
    private JsonNode fetchPage(String slug, int page) throws JsonProcessingException {
        String url = baseUrl + String.format(LIST_PATH_TEMPLATE, slug);
        if (page > 1) {
            url = url + String.format(PAGE_QUERY_TEMPLATE, page);
        }

        String html = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(45));

        if (html == null || html.isBlank()) {
            return null;
        }

        Element nextData = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__");
        if (nextData == null) {
            return null;
        }
        return objectMapper.readTree(nextData.data());
    }

    private JsonNode jobsNode(JsonNode root) {
        if (root == null) {
            return null;
        }
        return root.path("props").path("pageProps").path("initialState").path("jobs");
    }

    private void collectJobs(JsonNode items, String slug, List<RawAggregatorJob> jobs, Set<String> seenExternalIds) {
        for (JsonNode item : items) {
            RawAggregatorJob job = mapJob(item, slug);
            if (job == null) {
                continue;
            }
            String externalId = job.externalId();
            if (externalId != null && !seenExternalIds.add(externalId)) {
                continue;
            }
            jobs.add(job);
        }
    }

    private RawAggregatorJob mapJob(JsonNode node, String slug) {
        try {
            String externalId = textOrNull(node, "id");
            if (externalId == null) {
                // Fallback keeps the numeric id as the stable key but avoids dropping an id-less posting
                externalId = textOrNull(node, "idParam");
            }

            String title = textOrNull(node, "title");
            if (title != null) {
                title = title.trim();
            }

            String idParam = textOrNull(node, "idParam");
            String applyUrl = (idParam == null)
                    ? null
                    : baseUrl + String.format(LIST_PATH_TEMPLATE, slug) + "/" + idParam;

            String location = buildLocation(node);
            String rawJson = node.toString();
            LocalDate postedDate = parseDate(textOrNull(node, "createdAt"));

            return new RawAggregatorJob(
                    externalId, title, null, location, null, applyUrl,
                    postedDate, null, null, null, rawJson
            );
        } catch (Exception e) {
            log.warn("Join: failed to map job node: {}", e.getMessage());
            return null;
        }
    }

    private String buildLocation(JsonNode node) {
        String city = textOrNull(node.path("city"), "cityName");
        String countryCode = textOrNull(node.path("country"), "iso3166");

        if (city != null && countryCode != null) {
            return city + ", " + countryCode;
        } else if (city != null) {
            return city;
        } else if (countryCode != null) {
            return countryCode;
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
                return LocalDate.parse(dateStr);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
