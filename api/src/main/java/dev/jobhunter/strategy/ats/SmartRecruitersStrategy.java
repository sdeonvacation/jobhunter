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

@Slf4j
@Component
public class SmartRecruitersStrategy extends AbstractAtsStrategy {

    private static final String API_URL = "https://api.smartrecruiters.com/v1/companies/%s/postings";
    private static final String DETAIL_URL = "https://api.smartrecruiters.com/v1/companies/%s/postings/%s";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;
    private static final Duration DETAIL_TIMEOUT = Duration.ofSeconds(15);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public SmartRecruitersStrategy(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.SMARTRECRUITERS);
    }

    @Override
    public String name() {
        return "smartrecruiters";
    }


    @Override
    public FetchResult fetch(FetchContext context) {
        CareerEndpoint endpoint = context.endpoint();
        String slug = endpoint.getAtsSlug();
        Instant start = Instant.now();

        try {
            List<RawAggregatorJob> allJobs = new ArrayList<>();
            int offset = 0;
            int total = Integer.MAX_VALUE;

            while (offset < total && allJobs.size() < MAX_PAGES * PAGE_SIZE) {
                String url = String.format(API_URL, slug) + "?limit=" + PAGE_SIZE + "&offset=" + offset;

                String response = webClient.get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(45));

                if (response == null || response.isBlank()) {
                    break;
                }

                JsonNode root = objectMapper.readTree(response);
                int pageTotal = root.path("totalFound").asInt(0);
                if (pageTotal > 0) {
                    total = pageTotal;
                }

                JsonNode content = root.path("content");
                if (!content.isArray() || content.isEmpty()) {
                    break;
                }

                for (JsonNode node : content) {
                    RawAggregatorJob job = mapJob(node, slug);
                    if (job != null) {
                        allJobs.add(job);
                    }
                }

                offset += PAGE_SIZE;
            }

            if (allJobs.isEmpty()) {
                log.info("SmartRecruiters [{}]: no jobs found", slug);
                return FetchResult.empty(elapsed(start));
            }

            log.info("SmartRecruiters [{}]: extracted {} jobs (total: {})", slug, allJobs.size(), total);
            return FetchResult.success(allJobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("SmartRecruiters [{}]: company not found (404)", slug);
            return FetchResult.empty(elapsed(start));
        } catch (Exception e) {
            log.error("SmartRecruiters [{}]: extraction failed: {}", slug, e.getMessage());
            return FetchResult.error(e.getMessage(), elapsed(start));
        }
    }

    private RawAggregatorJob mapJob(JsonNode node, String companySlug) {
        try {
            String externalId = node.path("id").asText(null);
            if (externalId == null) {
                externalId = node.path("uuid").asText(null);
            }
            String title = truncate(node.path("name").asText(null), 500);

            // Location: prefer fullLocation (has country name written out, avoids
            // ISO region code "BE" being misread as Belgium by CityCountryResolver)
            JsonNode loc = node.path("location");
            String city = loc.path("city").asText("");
            String country = loc.path("country").asText("");
            String fullLocation = loc.path("fullLocation").asText("").trim();
            String location;
            if (!fullLocation.isBlank()) {
                location = truncate(fullLocation, 500);
            } else {
                location = truncate(buildLocation(city, "", country), 500);
            }

            // Apply URL - use public jobs site, not the API ref
            String companyId = node.path("company").path("identifier").asText("");
            String applyUrl = "https://jobs.smartrecruiters.com/" + companyId + "/" + externalId;

            // Date
            String releasedDate = node.path("releasedDate").asText(null);
            LocalDate postedDate = parseDate(releasedDate);

            String rawJson = node.toString();

            return new RawAggregatorJob(
                    externalId, title, null, location, null, applyUrl,
                    postedDate, null, null, null, rawJson
            );
        } catch (Exception e) {
            log.warn("SmartRecruiters: failed to map job: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fetch description for a single posting from the detail endpoint.
     * Used by backfill process for KEEP jobs only.
     */
    public String fetchDescription(String companySlug, String postingId) {
        try {
            String url = String.format(DETAIL_URL, companySlug, postingId);
            String response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(DETAIL_TIMEOUT);

            if (response == null || response.isBlank()) return null;

            JsonNode root = objectMapper.readTree(response);
            JsonNode sections = root.path("jobAd").path("sections");
            if (sections.isMissingNode()) return null;

            StringBuilder desc = new StringBuilder();
            for (String key : List.of("jobDescription", "qualifications", "additionalInformation")) {
                String text = sections.path(key).path("text").asText("");
                if (!text.isBlank()) {
                    if (desc.length() > 0) desc.append("\n");
                    desc.append(text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
                }
            }
            return desc.length() > 0 ? desc.toString() : null;
        } catch (Exception e) {
            log.debug("SmartRecruiters: failed to fetch description for {}/{}: {}", companySlug, postingId, e.getMessage());
            return null;
        }
    }

    private String buildLocation(String city, String region, String country) {
        StringBuilder sb = new StringBuilder();
        if (!city.isBlank()) sb.append(city);
        if (!region.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(region);
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
            return LocalDate.parse(dateStr.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return null;
        }
    }
}
