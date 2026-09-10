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
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Scrapes ScreenLoop careers boards (https://app.screenloop.com/careers/{subdomain}).
 *
 * <p>Boards are server-rendered; both the list page and each {@code /job_posts/{id}} detail page
 * embed a {@code <div data-react-props="{...}">} element holding entity-escaped JSON. Jsoup
 * decodes the attribute value, so it parses directly as JSON.</p>
 */
@Slf4j
@Component
public class ScreenLoopStrategy extends AbstractAtsStrategy {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);
    private static final long DETAIL_FETCH_DELAY_MS = 150;
    private static final int MAX_DESCRIPTION_LENGTH = 10_000;
    private static final String DETAIL_PATH_SUFFIX = "/job_posts/";

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScreenLoopStrategy(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.SCREENLOOP);
    }

    @Override
    public String name() {
        return "screenloop";
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        CareerEndpoint endpoint = context.endpoint();
        String baseUrl = stripTrailingSlash(endpoint.getUrl());
        Instant start = Instant.now();

        try {
            JsonNode listProps = parseReactProps(fetchHtml(baseUrl));
            if (listProps == null) {
                log.warn("ScreenLoop [{}]: list page missing data-react-props", baseUrl);
                return FetchResult.error("no data-react-props found on list page", elapsed(start));
            }

            String companyName = firstNonBlank(textOrNull(listProps.path("companyInfo"), "name"));
            JsonNode jobPosts = listProps.path("jobPosts");
            if (!jobPosts.isArray() || jobPosts.isEmpty()) {
                log.info("ScreenLoop [{}]: no job posts on board", baseUrl);
                return FetchResult.empty(elapsed(start));
            }
            log.info("ScreenLoop [{}]: list page contains {} job posts", baseUrl, jobPosts.size());

            List<RawAggregatorJob> jobs = new ArrayList<>();
            int attempted = 0;
            for (JsonNode jobPost : jobPosts) {
                if (attempted > 0) {
                    sleep(DETAIL_FETCH_DELAY_MS);
                }
                attempted++;
                try {
                    RawAggregatorJob job = buildJob(jobPost, baseUrl, companyName);
                    if (job != null) {
                        jobs.add(job);
                    }
                } catch (Exception e) {
                    log.warn("ScreenLoop [{}]: failed to fetch job post {}: {}",
                            baseUrl, jobPost.path("id").asText("?"), e.getMessage());
                }
            }

            if (jobs.isEmpty()) {
                log.info("ScreenLoop [{}]: no usable jobs extracted", baseUrl);
                return FetchResult.empty(elapsed(start));
            }
            log.info("ScreenLoop [{}]: extracted {} jobs", baseUrl, jobs.size());
            return FetchResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.Forbidden e) {
            log.warn("ScreenLoop [{}]: access forbidden (403)", baseUrl);
            return FetchResult.protectedEndpoint(elapsed(start));
        } catch (WebClientResponseException.Unauthorized e) {
            log.warn("ScreenLoop [{}]: unauthorized (401)", baseUrl);
            return FetchResult.protectedEndpoint(elapsed(start));
        } catch (Exception e) {
            log.error("ScreenLoop [{}]: extraction failed: {}", baseUrl, e.getMessage());
            return FetchResult.error(e.getMessage(), elapsed(start));
        }
    }

    /**
     * Fetches and maps a single job post detail page. Returns {@code null} (skips the job) when
     * the detail page cannot be parsed; detail failures are best-effort like SuccessFactorsStrategy.
     */
    private RawAggregatorJob buildJob(JsonNode jobPost, String baseUrl, String companyName) {
        String id = jobPost.path("id").asText(null);
        String listTitle = textOrNull(jobPost, "name");
        if (id == null || id.isBlank() || listTitle == null) {
            log.warn("ScreenLoop [{}]: skipping job post missing id or title", baseUrl);
            return null;
        }

        String detailUrl = baseUrl + DETAIL_PATH_SUFFIX + id;
        JsonNode detailProps = parseReactProps(fetchHtml(detailUrl));
        if (detailProps == null) {
            log.warn("ScreenLoop [{}]: detail page missing data-react-props", detailUrl);
            return null;
        }

        JsonNode preview = detailProps.path("previewJobPost");
        JsonNode listLocation = jobPost.path("location");

        String title = firstNonBlank(textOrNull(preview, "name"), listTitle);
        String location = firstNonBlank(
                textOrNull(preview, "location"),
                textOrNull(listLocation, "name"),
                textOrNull(preview, "locationCountry"),
                textOrNull(listLocation, "countryCode"));
        String externalId = firstNonBlank(textOrNull(preview, "jobPostId"), id);
        String orgName = firstNonBlank(
                textOrNull(detailProps.path("companyInfo"), "name"), companyName);
        String description = truncate(
                stripHtml(detailProps.path("jobPostHtmlString").asText(null)), MAX_DESCRIPTION_LENGTH);

        return new RawAggregatorJob(
                externalId,
                title,
                orgName,
                location,
                description,
                detailUrl,
                null,
                null,
                null,
                null,
                detailProps.toString());
    }

    private String fetchHtml(String url) {
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block(REQUEST_TIMEOUT);
    }

    /**
     * Locates the first {@code [data-react-props]} element and parses its (entity-decoded by Jsoup)
     * attribute value as JSON. Returns {@code null} on any failure.
     */
    private JsonNode parseReactProps(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        try {
            Element element = Jsoup.parse(html).selectFirst("[data-react-props]");
            if (element == null) {
                return null;
            }
            String value = element.attr("data-react-props");
            if (value == null || value.isBlank()) {
                return null;
            }
            return objectMapper.readTree(value);
        } catch (Exception e) {
            log.warn("ScreenLoop: failed to parse data-react-props JSON: {}", e.getMessage());
            return null;
        }
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private String stripTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
