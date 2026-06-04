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
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
public class SmartRecruitersExtractor implements JobExtractor {

    private static final String API_URL = "https://api.smartrecruiters.com/v1/companies/%s/postings";
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 20;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public SmartRecruitersExtractor(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.SMARTRECRUITERS);
    }

    @Override
    public boolean canExtract(CareerEndpoint endpoint) {
        return endpoint.getAtsSlug() != null && !endpoint.getAtsSlug().isBlank();
    }

    @Override
    public ExtractionResult extract(CareerEndpoint endpoint) {
        String slug = endpoint.getAtsSlug();
        Instant start = Instant.now();

        try {
            List<RawJobData> allJobs = new ArrayList<>();
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
                    RawJobData job = mapJob(node);
                    if (job != null) {
                        allJobs.add(job);
                    }
                }

                offset += PAGE_SIZE;
            }

            if (allJobs.isEmpty()) {
                log.info("SmartRecruiters [{}]: no jobs found", slug);
                return ExtractionResult.empty(elapsed(start));
            }

            log.info("SmartRecruiters [{}]: extracted {} jobs (total: {})", slug, allJobs.size(), total);
            return ExtractionResult.success(allJobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("SmartRecruiters [{}]: company not found (404)", slug);
            return ExtractionResult.empty(elapsed(start));
        } catch (Exception e) {
            log.error("SmartRecruiters [{}]: extraction failed: {}", slug, e.getMessage());
            return ExtractionResult.error(e.getMessage(), elapsed(start));
        }
    }

    private RawJobData mapJob(JsonNode node) {
        try {
            String externalId = node.path("id").asText(null);
            if (externalId == null) {
                externalId = node.path("uuid").asText(null);
            }
            String title = truncate(node.path("name").asText(null), 500);

            // Location: city + country
            JsonNode loc = node.path("location");
            String city = loc.path("city").asText("");
            String country = loc.path("country").asText("");
            String region = loc.path("region").asText("");
            String location = truncate(buildLocation(city, region, country), 500);

            // Apply URL
            String applyUrl = node.path("ref").asText(null);
            if (applyUrl == null || applyUrl.isBlank()) {
                String companyId = node.path("company").path("identifier").asText("");
                applyUrl = "https://jobs.smartrecruiters.com/" + companyId + "/" + externalId;
            }

            // Date
            String releasedDate = node.path("releasedDate").asText(null);
            LocalDate postedDate = parseDate(releasedDate);

            String rawJson = node.toString();

            return new RawJobData(
                    externalId, title, location, null, applyUrl,
                    rawJson, null, null, null, postedDate
            );
        } catch (Exception e) {
            log.warn("SmartRecruiters: failed to map job: {}", e.getMessage());
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

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
