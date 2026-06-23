package dev.jobhunter.strategy.aggregator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetch strategy that runs the jobspy-js CLI subprocess to scrape Indeed jobs.
 * Extracts transport logic previously embedded in IndeedJobSearchService.
 */
@Slf4j
@Component
public class CliStrategy implements FetchStrategy {

    private static final Pattern JK_PATTERN = Pattern.compile("jk=([a-f0-9]+)");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String npxPath;

    public CliStrategy(@Value("${indeed.npx-path:npx}") String npxPath) {
        this.npxPath = npxPath;
    }

    @Override
    public String name() {
        return "jobspy-cli";
    }

    @Override
    public boolean supports(AtsType type) {
        return type == AtsType.INDEED;
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        Instant start = Instant.now();
        List<String> keywords = context.keywords();
        List<String> locations = context.locations();

        if (keywords == null || keywords.isEmpty() || locations == null || locations.isEmpty()) {
            return FetchResult.empty(Duration.between(start, Instant.now()));
        }

        int resultsWanted = context.maxResults();
        int hoursOld = context.config() != null
                ? ((Number) context.config().getOrDefault("hours-old", 24)).intValue()
                : 24;
        long timeoutSeconds = context.config() != null
                ? ((Number) context.config().getOrDefault("timeout-seconds", DEFAULT_TIMEOUT.toSeconds())).longValue()
                : DEFAULT_TIMEOUT.toSeconds();

        List<RawAggregatorJob> allJobs = new ArrayList<>();
        String lastError = null;
        int errorCount = 0;

        for (String keyword : keywords) {
            for (String location : locations) {
                try {
                    List<RawAggregatorJob> jobs = runJobspy(keyword, location, resultsWanted, hoursOld, timeoutSeconds);
                    allJobs.addAll(jobs);
                } catch (Exception e) {
                    log.error("jobspy-js CLI failed for '{}' in '{}': {}", keyword, location, e.getMessage());
                    lastError = e.getMessage();
                    errorCount++;
                }
            }
        }

        Duration elapsed = Duration.between(start, Instant.now());
        if (allJobs.isEmpty()) {
            if (errorCount > 0) {
                return FetchResult.error("All searches failed (" + errorCount + "): " + lastError, elapsed);
            }
            return FetchResult.empty(elapsed);
        }
        return FetchResult.success(allJobs, elapsed);
    }

    List<RawAggregatorJob> runJobspy(String keyword, String location, int resultsWanted,
                                     int hoursOld, long timeoutSeconds) throws Exception {
        File outputFile = File.createTempFile("jobspy-", ".json");
        outputFile.deleteOnExit();

        try {
            List<String> command = new ArrayList<>(List.of(
                    npxPath, "-y", "jobspy-js",
                    "-s", "indeed",
                    "-q", keyword,
                    "-l", location,
                    "-n", String.valueOf(resultsWanted),
                    "-c", location.toLowerCase(),
                    "-o", outputFile.getAbsolutePath()
            ));
            if (hoursOld > 0) {
                command.add("--hours-old");
                command.add(String.valueOf(hoursOld));
            }

            ProcessBuilder pb = new ProcessBuilder(command)
                    .redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("jobspy-js process timed out after " + timeoutSeconds + "s");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String stderr = new String(process.getInputStream().readAllBytes());
                throw new RuntimeException("jobspy-js exited with code " + exitCode + ": "
                        + stderr.substring(0, Math.min(stderr.length(), 200)));
            }

            if (!outputFile.exists() || outputFile.length() == 0) {
                return List.of();
            }

            String json = Files.readString(outputFile.toPath());
            List<JobspyResult> results = MAPPER.readValue(json, new TypeReference<>() {});
            return mapToRawJobs(results);
        } finally {
            outputFile.delete();
        }
    }

    List<RawAggregatorJob> mapToRawJobs(List<JobspyResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }

        List<RawAggregatorJob> jobs = new ArrayList<>();
        for (JobspyResult r : results) {
            if (r.title() == null || r.title().isBlank()) continue;
            if (r.company() == null || r.company().isBlank()) continue;

            String externalId = extractExternalId(r.id(), r.job_url());
            String jobUrl = r.job_url() != null ? r.job_url() : "";
            LocalDate postedDate = parseDate(r.date_posted());

            jobs.add(new RawAggregatorJob(
                    externalId,
                    r.title(),
                    r.company(),
                    r.location(),
                    r.description(),
                    jobUrl,
                    postedDate,
                    null, null, null, // salary not provided by jobspy
                    null // rawJson
            ));
        }
        return jobs;
    }

    String extractExternalId(String id, String url) {
        if (id != null && !id.isBlank()) {
            return id;
        }
        if (url != null) {
            Matcher matcher = JK_PATTERN.matcher(url);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return Integer.toHexString((url != null ? url : "").hashCode());
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * JSON structure returned by jobspy-js CLI.
     */
    record JobspyResult(
            String id,
            String site,
            String job_url,
            String job_url_direct,
            String title,
            String company,
            String location,
            String date_posted,
            Boolean is_remote,
            String description
    ) {}
}
