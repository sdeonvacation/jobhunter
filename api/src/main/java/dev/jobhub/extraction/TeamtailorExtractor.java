package dev.jobhub.extraction;

import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.model.enums.AtsType;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Extracts jobs from TeamTailor career pages via their public RSS feed.
 * RSS feed available at {careersUrl}/jobs.rss (all jobs, no pagination needed).
 */
@Slf4j
@Component
public class TeamtailorExtractor implements JobExtractor {

    private static final Pattern JOB_ID_PATTERN = Pattern.compile("/jobs/(\\d+)-");
    private static final DateTimeFormatter RSS_DATE_FORMAT = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]*>");

    private final WebClient webClient;

    @org.springframework.beans.factory.annotation.Autowired
    public TeamtailorExtractor(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Set<AtsType> supportedTypes() {
        return Set.of(AtsType.TEAMTAILOR);
    }

    @Override
    public boolean canExtract(CareerEndpoint endpoint) {
        return endpoint.getAtsType() == AtsType.TEAMTAILOR
                && endpoint.getUrl() != null
                && !endpoint.getUrl().isBlank();
    }

    @Override
    public ExtractionResult extract(CareerEndpoint endpoint) {
        Instant start = Instant.now();
        String rssUrl = buildRssUrl(endpoint.getUrl());

        try {
            String responseBody = webClient.get()
                    .uri(rssUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(45));

            if (responseBody == null || responseBody.isBlank()) {
                return ExtractionResult.empty(elapsed(start));
            }

            Document doc = Jsoup.parse(responseBody, "", Parser.xmlParser());
            Elements items = doc.getElementsByTag("item");

            if (items.isEmpty()) {
                return ExtractionResult.empty(elapsed(start));
            }

            List<RawJobData> jobs = new ArrayList<>();
            for (Element item : items) {
                RawJobData job = mapJob(item);
                if (job != null) {
                    jobs.add(job);
                }
            }

            if (jobs.isEmpty()) {
                return ExtractionResult.empty(elapsed(start));
            }

            log.info("Teamtailor [{}]: extracted {} jobs", endpoint.getUrl(), jobs.size());
            return ExtractionResult.success(jobs, elapsed(start));

        } catch (WebClientResponseException.NotFound e) {
            log.warn("Teamtailor [{}]: RSS feed not found (404)", rssUrl);
            return ExtractionResult.empty(elapsed(start));
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                log.warn("Teamtailor [{}]: rate limited (429)", rssUrl);
                return ExtractionResult.rateLimited(elapsed(start));
            }
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                log.warn("Teamtailor [{}]: protected endpoint ({})", rssUrl, e.getStatusCode().value());
                return ExtractionResult.protectedEndpoint(elapsed(start));
            }
            log.error("Teamtailor [{}]: HTTP {} - {}", rssUrl, e.getStatusCode(), e.getMessage());
            return ExtractionResult.error("HTTP " + e.getStatusCode(), elapsed(start));
        } catch (Exception e) {
            log.error("Teamtailor [{}]: extraction failed", rssUrl, e);
            return ExtractionResult.error(e.getMessage(), elapsed(start));
        }
    }

    /**
     * Derives RSS feed URL from the career page URL.
     * Handles: .../jobs → .../jobs.rss, .../other → .../jobs.rss
     */
    String buildRssUrl(String url) {
        String normalized = url.replaceAll("/+$", "");
        if (normalized.endsWith("/jobs")) {
            return normalized + ".rss";
        }
        return normalized + "/jobs.rss";
    }

    private RawJobData mapJob(Element item) {
        try {
            String title = truncate(textOf(item, "title"), 500);
            String link = textOf(item, "link");
            String guid = textOf(item, "guid");
            String description = extractDescription(item);
            String location = extractLocation(item);
            LocalDate postedDate = parseRssDate(textOf(item, "pubDate"));

            // Use guid as external ID, fallback to numeric ID from URL
            String externalId = guid != null ? guid : extractIdFromUrl(link);

            if (title == null || link == null) {
                return null;
            }

            return new RawJobData(
                    externalId, title, location, description, link,
                    item.outerHtml(), null, null, null, postedDate
            );
        } catch (Exception e) {
            log.warn("Teamtailor: failed to map job item: {}", e.getMessage());
            return null;
        }
    }

    private String extractLocation(Element item) {
        // TeamTailor uses tt:location elements within tt:locations
        Elements locations = item.getElementsByTag("tt:location");

        String locationStr = locations.stream()
                .map(loc -> {
                    String city = textOf(loc, "tt:city");
                    String country = textOf(loc, "tt:country");

                    if (city != null && country != null) {
                        return city + ", " + country;
                    }
                    // Fallback to tt:name which is often the display name
                    String name = textOf(loc, "tt:name");
                    return name != null ? name : (city != null ? city : country);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.joining("; "));

        // Append remote status if available
        String remoteStatus = textOf(item, "remoteStatus");
        if (remoteStatus != null && !remoteStatus.isBlank()) {
            locationStr = locationStr.isEmpty() ? remoteStatus : locationStr + " (" + remoteStatus + ")";
        }

        return truncate(locationStr.isEmpty() ? null : locationStr, 500);
    }

    private String extractDescription(Element item) {
        Element descEl = item.getElementsByTag("description").first();
        if (descEl == null) {
            return null;
        }
        // RSS description contains HTML-escaped content; Jsoup .text() unescapes it
        String html = descEl.text();
        if (html == null || html.isBlank()) {
            return null;
        }
        return stripHtml(html);
    }

    private String extractIdFromUrl(String url) {
        if (url == null) return null;
        Matcher matcher = JOB_ID_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private LocalDate parseRssDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(dateStr, RSS_DATE_FORMAT).toLocalDate();
        } catch (Exception e) {
            try {
                return LocalDate.parse(dateStr);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private String stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        return HTML_TAG_PATTERN.matcher(html).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    private String textOf(Element parent, String tagName) {
        Element el = parent.getElementsByTag(tagName).first();
        if (el == null) return null;
        String text = el.text();
        return text.isBlank() ? null : text;
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
