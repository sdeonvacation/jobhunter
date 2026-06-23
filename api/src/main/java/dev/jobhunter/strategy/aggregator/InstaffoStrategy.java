package dev.jobhunter.strategy.aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class InstaffoStrategy extends SitemapScrapeStrategy {

    private static final Pattern URL_PATTERN =
            Pattern.compile("^https://jobs\\.instaffo\\.com/en/job/.+$");
    private static final Pattern EXTERNAL_ID_PATTERN =
            Pattern.compile("-([a-f0-9]{12})$");
    private static final Pattern SALARY_PATTERN =
            Pattern.compile("(\\d{1,3}(?:,\\d{3}))\\s*[-\u2013]\\s*(\\d{1,3}(?:,\\d{3}))\\s*\u20ac");

    private final ObjectMapper objectMapper;

    public InstaffoStrategy(WebClient webClient, JobPostingRepository jobPostingRepository) {
        super(webClient, jobPostingRepository);
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String name() { return "instaffo"; }

    @Override
    protected Pattern urlFilterPattern() { return URL_PATTERN; }

    @Override
    protected JobSource jobSource() { return JobSource.INSTAFFO; }

    @Override
    protected String extractExternalId(String url) {
        Matcher m = EXTERNAL_ID_PATTERN.matcher(url);
        return m.find() ? m.group(1) : String.valueOf(url.hashCode());
    }

    @Override
    protected Optional<RawAggregatorJob> parsePage(String html, String url, String externalId) {
        Document doc = Jsoup.parse(html);

        // Primary: JSON-LD JobPosting extraction
        JsonNode jsonLd = extractJobPostingJsonLd(doc);

        String title = null;
        String company = null;
        String location = null;
        String description = null;
        LocalDate postedDate = null;
        String rawJson = null;

        if (jsonLd != null) {
            title = textOrNull(jsonLd, "title");
            String co = jsonLd.path("hiringOrganization").path("name").asText(null);
            company = (co != null && !co.isBlank()) ? co : null;
            location = extractLocation(jsonLd);
            description = textOrNull(jsonLd, "description");
            postedDate = parseDate(textOrNull(jsonLd, "datePosted"));
            rawJson = jsonLd.toString();
        }

        // Fallbacks when JSON-LD absent or incomplete
        if (title == null) {
            Element h1 = doc.selectFirst("h1");
            title = (h1 != null && !h1.text().isBlank()) ? h1.text() : null;
        }
        if (company == null) {
            String docTitle = doc.title();
            int atIdx = docTitle.lastIndexOf(" at ");
            if (atIdx >= 0) {
                String extracted = docTitle.substring(atIdx + 4).trim();
                company = extracted.isBlank() ? null : extracted;
            }
        }

        if (title == null || title.isBlank()) {
            log.warn("[instaffo] No title extractable for job at {}", url);
            return Optional.empty();
        }

        // Supplementary: salary from visible HTML (JSON-LD has no baseSalary on Instaffo)
        BigDecimal salaryMin = null;
        BigDecimal salaryMax = null;
        String salaryCurrency = null;
        Matcher salaryMatcher = SALARY_PATTERN.matcher(html);
        if (salaryMatcher.find()) {
            try {
                salaryMin = new BigDecimal(salaryMatcher.group(1).replace(",", ""));
                salaryMax = new BigDecimal(salaryMatcher.group(2).replace(",", ""));
                salaryCurrency = "EUR";
            } catch (NumberFormatException e) {
                log.debug("[instaffo] Could not parse salary figures for {}", url);
            }
        }

        return Optional.of(new RawAggregatorJob(
                externalId,
                title.trim(),
                company,
                location,
                description,
                url,
                postedDate,
                salaryMin,
                salaryMax,
                salaryCurrency,
                rawJson
        ));
    }

    // --- Private helpers ---

    private JsonNode extractJobPostingJsonLd(Document doc) {
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                JsonNode node = objectMapper.readTree(script.data());
                if ("JobPosting".equals(node.path("@type").asText())) {
                    return node;
                }
            } catch (Exception e) {
                log.trace("[instaffo] Malformed JSON-LD block: {}", e.getMessage());
            }
        }
        return null;
    }

    private String extractLocation(JsonNode jsonLd) {
        JsonNode jobLocation = jsonLd.path("jobLocation");
        List<String> cities = new ArrayList<>();
        if (jobLocation.isArray()) {
            for (JsonNode loc : jobLocation) {
                String city = loc.path("address").path("addressLocality").asText(null);
                if (city != null && !city.isBlank()) cities.add(city);
            }
        } else if (!jobLocation.isMissingNode() && !jobLocation.isNull()) {
            String city = jobLocation.path("address").path("addressLocality").asText(null);
            if (city != null && !city.isBlank()) cities.add(city);
        }
        return cities.isEmpty() ? null : String.join(", ", cities);
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode val = node.path(field);
        if (val.isMissingNode() || val.isNull()) return null;
        String text = val.asText();
        return text.isBlank() ? null : text;
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null) return null;
        try {
            String datePart = dateStr.length() > 10 ? dateStr.substring(0, 10) : dateStr;
            return LocalDate.parse(datePart);
        } catch (DateTimeParseException e) {
            log.trace("[instaffo] Could not parse date: {}", dateStr);
            return null;
        }
    }
}
