package dev.jobhunter.strategy.aggregator;

import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class JobgetherStrategy implements FetchStrategy {

    private static final int DEFAULT_MAX_PAGES = 10;
    private static final int JOBS_PER_PAGE = 50;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private static final long PAGE_DELAY_MS = 500L;

    // Field patterns for Astro SSR [0,value] serialization format
    private static final Pattern PAT_ID =
            Pattern.compile("\"_id\":\\[0,\"([a-f0-9]{24})\"\\]");
    private static final Pattern PAT_TITLE =
            Pattern.compile("\"title\":\\[0,\"([^\"]+)\"\\]");
    private static final Pattern PAT_APPLY_URL =
            Pattern.compile("\"applyUrl\":\\[0,\"(https?:[^\"]+)\"\\]");
    private static final Pattern PAT_REQUIRED_LOCATIONS =
            Pattern.compile("\"requiredLocations\":\\[0,\"([^\"]+)\"\\]");
    private static final Pattern PAT_CREATED_AT =
            Pattern.compile("\"createdAt\":\\[0,\"([^\"]+)\"\\]");
    // companyData._id always precedes name in the serialized object
    private static final Pattern PAT_COMPANY_NAME =
            Pattern.compile("\"companyData\":\\[0,\\{\"_id\":\\[0,\"[^\"]+\"\\],\"name\":\\[0,\"([^\"]+)\"\\]");
    // salary is a flat object: {average,currency,max,min} — no nested braces
    private static final Pattern PAT_SALARY_MIN =
            Pattern.compile("\"salary\":\\[0,\\{[^}]*\"min\":\\[0,(\\d+)");
    private static final Pattern PAT_SALARY_MAX =
            Pattern.compile("\"salary\":\\[0,\\{[^}]*\"max\":\\[0,(\\d+)");
    private static final Pattern PAT_SALARY_CURRENCY =
            Pattern.compile("\"salary\":\\[0,\\{[^}]*\"currency\":\\[0,\"([^\"]+)\"");

    private final WebClient webClient;

    public JobgetherStrategy(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String name() {
        return "jobgether";
    }

    @Override
    public boolean supports(AtsType type) {
        return false;
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        Instant start = Instant.now();
        String baseUrl = (String) context.config().get("url");

        if (baseUrl == null || baseUrl.isBlank()) {
            return FetchResult.error("No URL configured for jobgether source", elapsed(start));
        }

        List<RawAggregatorJob> allJobs = new ArrayList<>();
        int page = 1;

        try {
            while (page <= DEFAULT_MAX_PAGES && allJobs.size() < context.maxResults()) {
                String url = baseUrl + "?page=" + page;
                log.debug("Fetching Jobgether page {} from {}", page, url);

                String html = webClient.get()
                        .uri(url)
                        .header("User-Agent", USER_AGENT)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(30));

                if (html == null || html.isBlank()) {
                    log.warn("Empty response from Jobgether at {}", url);
                    break;
                }

                String decoded = html
                        .replace("&quot;", "\"")
                        .replace("&#34;", "\"")
                        .replace("&amp;", "&");

                List<RawAggregatorJob> pageJobs = parseJobs(decoded);
                log.info("Jobgether page {}: {} jobs from {}", page, pageJobs.size(), url);
                allJobs.addAll(pageJobs);

                if (pageJobs.size() < JOBS_PER_PAGE) {
                    break; // last page
                }

                page++;
                if (page <= DEFAULT_MAX_PAGES && allJobs.size() < context.maxResults()) {
                    Thread.sleep(PAGE_DELAY_MS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Jobgether fetch interrupted at page {}", page);
        } catch (Exception e) {
            log.error("Jobgether fetch failed for {}: {}", baseUrl, e.getMessage());
            if (allJobs.isEmpty()) {
                return FetchResult.error("Fetch failed: " + e.getMessage(), elapsed(start));
            }
        }

        if (allJobs.isEmpty()) {
            return FetchResult.empty(elapsed(start));
        }

        List<RawAggregatorJob> limited = allJobs.size() > context.maxResults()
                ? allJobs.subList(0, context.maxResults())
                : allJobs;

        log.info("Jobgether fetched {} jobs total from {}", limited.size(), baseUrl);
        return FetchResult.success(limited, elapsed(start));
    }

    private List<RawAggregatorJob> parseJobs(String decoded) {
        List<String> ids = findAll(decoded, PAT_ID);
        if (ids.isEmpty()) {
            log.debug("No job IDs found in Jobgether response");
            return List.of();
        }

        List<String> titles = findAll(decoded, PAT_TITLE);
        List<String> applyUrls = findAll(decoded, PAT_APPLY_URL);
        List<String> locations = findAll(decoded, PAT_REQUIRED_LOCATIONS);
        List<String> createdAts = findAll(decoded, PAT_CREATED_AT);
        List<String> companyNames = findAll(decoded, PAT_COMPANY_NAME);
        // Salary is optional — lists may be shorter than ids; get() handles out-of-bounds
        List<String> salaryMins = findAll(decoded, PAT_SALARY_MIN);
        List<String> salaryMaxes = findAll(decoded, PAT_SALARY_MAX);
        List<String> salaryCurrencies = findAll(decoded, PAT_SALARY_CURRENCY);

        int count = ids.size();
        if (titles.size() != count || applyUrls.size() != count) {
            log.warn("Jobgether field count mismatch: ids={}, titles={}, applyUrls={}",
                    count, titles.size(), applyUrls.size());
        }

        List<RawAggregatorJob> jobs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = ids.get(i);
            String title = get(titles, i);
            String applyUrl = get(applyUrls, i);

            if (title == null || applyUrl == null) {
                log.debug("Skipping Jobgether job {} — missing title or applyUrl", id);
                continue;
            }

            jobs.add(new RawAggregatorJob(
                    id,
                    title,
                    get(companyNames, i),
                    get(locations, i),
                    null,       // no description on list page — enriched later
                    applyUrl,
                    parseDate(get(createdAts, i)),
                    parseSalary(get(salaryMins, i)),
                    parseSalary(get(salaryMaxes, i)),
                    get(salaryCurrencies, i),
                    null        // no rawJson needed
            ));
        }

        return jobs;
    }

    private List<String> findAll(String text, Pattern pattern) {
        List<String> results = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            results.add(m.group(1));
        }
        return results;
    }

    private String get(List<String> list, int index) {
        return index < list.size() ? list.get(index) : null;
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.substring(0, 10));
        } catch (Exception e) {
            log.trace("Could not parse Jobgether date: {}", dateStr);
            return null;
        }
    }

    private BigDecimal parseSalary(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            BigDecimal bd = new BigDecimal(value);
            return bd.compareTo(BigDecimal.ZERO) == 0 ? null : bd;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }
}
