package dev.jobhub.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.model.enums.AtsType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Map.entry;

@Slf4j
@Component
public class AshbyExtractor implements JobExtractor {

    private static final String DEFAULT_BASE_URL = "https://api.ashbyhq.com";
    private static final String PATH_TEMPLATE = "/posting-api/job-board/%s?includeCompensation=true";
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]*>");
    private static final Map<String, String> HTML_ENTITIES = Map.ofEntries(
            entry("&amp;", "&"),
            entry("&lt;", "<"),
            entry("&gt;", ">"),
            entry("&nbsp;", " "),
            entry("&quot;", "\""),
            entry("&#39;", "'"),
            entry("&apos;", "'")
    );
    private static final Pattern COMPENSATION_RANGE_PATTERN = Pattern.compile(
            "[^\\d]*(\\d[\\d,.]*)\\s*[-–]\\s*[^\\d]*(\\d[\\d,.]*)"
    );

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @org.springframework.beans.factory.annotation.Autowired
    public AshbyExtractor(WebClient webClient, ObjectMapper objectMapper) {
        this(webClient, objectMapper, DEFAULT_BASE_URL);
    }

    // Visible for testing
    AshbyExtractor(WebClient webClient, ObjectMapper objectMapper, String baseUrl) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.ASHBY);
    }

    @Override
    public ExtractionResult extract(CareerEndpoint endpoint) {
        Instant start = Instant.now();
        String slug = endpoint.getAtsSlug();

        try {
            String url = baseUrl + String.format(PATH_TEMPLATE, slug);
            String responseBody = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(45));

            if (responseBody == null || responseBody.isBlank()) {
                return ExtractionResult.empty(elapsed(start));
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode jobsNode = root.path("jobs");

            if (!jobsNode.isArray() || jobsNode.isEmpty()) {
                return ExtractionResult.empty(elapsed(start));
            }

            List<RawJobData> jobs = new ArrayList<>();
            for (JsonNode jobNode : jobsNode) {
                RawJobData job = mapJob(jobNode);
                if (job != null) {
                    jobs.add(job);
                }
            }

            if (jobs.isEmpty()) {
                return ExtractionResult.empty(elapsed(start));
            }

            log.info("Ashby [{}]: extracted {} jobs", slug, jobs.size());
            return ExtractionResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("Ashby [{}]: board not found (404)", slug);
            return ExtractionResult.empty(elapsed(start));
        } catch (WebClientResponseException e) {
            log.error("Ashby [{}]: HTTP {} - {}", slug, e.getStatusCode(), e.getMessage());
            return ExtractionResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("Ashby [{}]: extraction failed", slug, e);
            return ExtractionResult.error(e.getMessage(), elapsed(start));
        }
    }

    @Override
    public boolean canExtract(CareerEndpoint endpoint) {
        return endpoint.getAtsType() == AtsType.ASHBY
                && endpoint.getAtsSlug() != null
                && !endpoint.getAtsSlug().isBlank();
    }

    private RawJobData mapJob(JsonNode node) {
        try {
            String externalId = node.path("id").asText(null);
            String title = truncate(node.path("title").asText(null), 500);
            String location = truncate(node.path("location").asText(null), 500);

            String description = node.path("descriptionPlain").asText("");
            if (description.isBlank()) {
                description = stripHtml(node.path("descriptionHtml").asText(""));
            }

            String applyUrl = node.path("applyUrl").asText(null);
            String rawJson = node.toString();
            LocalDate postedDate = parseDate(node.path("publishedDate").asText(null));

            BigDecimal salaryMin = null;
            BigDecimal salaryMax = null;
            String salaryCurrency = null;

            JsonNode compensation = node.path("compensation");
            if (!compensation.isMissingNode() && !compensation.isNull()) {
                salaryCurrency = compensation.path("currency").asText(null);
                String summary = compensation.path("compensationTierSummary").asText(null);
                if (summary != null) {
                    BigDecimal[] range = parseCompensationRange(summary);
                    if (range != null) {
                        salaryMin = range[0];
                        salaryMax = range[1];
                    }
                }
            }

            return new RawJobData(
                    externalId, title, location, description, applyUrl,
                    rawJson, salaryMin, salaryMax, salaryCurrency, postedDate
            );
        } catch (Exception e) {
            log.warn("Ashby: failed to map job node: {}", e.getMessage());
            return null;
        }
    }

    private BigDecimal[] parseCompensationRange(String summary) {
        Matcher matcher = COMPENSATION_RANGE_PATTERN.matcher(summary);
        if (matcher.find()) {
            try {
                String minStr = matcher.group(1).replace(",", "");
                String maxStr = matcher.group(2).replace(",", "");
                return new BigDecimal[]{new BigDecimal(minStr), new BigDecimal(maxStr)};
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
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
                return null;
            }
        }
    }

    private String stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = HTML_TAG_PATTERN.matcher(html).replaceAll("");
        for (Map.Entry<String, String> entry : HTML_ENTITIES.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        return text.replaceAll("\\s+", " ").trim();
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
