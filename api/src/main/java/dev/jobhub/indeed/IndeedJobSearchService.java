package dev.jobhub.indeed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhub.filter.DeduplicationFilter;
import dev.jobhub.filter.FilterResult;
import dev.jobhub.filter.LanguageFilter;
import dev.jobhub.filter.LocationFilter;
import dev.jobhub.filter.RoleRelevanceFilter;
import dev.jobhub.filter.YoeFilter;
import dev.jobhub.model.Company;
import dev.jobhub.model.JobPosting;
import dev.jobhub.model.enums.AtsType;
import dev.jobhub.model.enums.CompanyStatus;
import dev.jobhub.model.enums.DiscoverySource;
import dev.jobhub.model.enums.FilterDecision;
import dev.jobhub.repository.CompanyRepository;
import dev.jobhub.repository.JobPostingRepository;
import dev.jobhub.service.PersonalProfile;
import dev.jobhub.service.PersonalProfileLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Searches Indeed for jobs via jobspy-js CLI and creates job postings with source=INDEED.
 * Applies language and YOE filters before persisting.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "discovery.providers.jobspy", name = "enabled", havingValue = "true")
public class IndeedJobSearchService {

    private static final Pattern JK_PATTERN = Pattern.compile("jk=([a-f0-9]+)");
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final JobPostingRepository jobPostingRepository;
    private final CompanyRepository companyRepository;
    private final PersonalProfileLoader profileLoader;
    private final LanguageFilter languageFilter;
    private final RoleRelevanceFilter roleRelevanceFilter;
    private final LocationFilter locationFilter;
    private final YoeFilter yoeFilter;
    private final DeduplicationFilter deduplicationFilter;

    public IndeedJobSearchService(JobPostingRepository jobPostingRepository,
                                  CompanyRepository companyRepository,
                                  PersonalProfileLoader profileLoader,
                                  LanguageFilter languageFilter,
                                  RoleRelevanceFilter roleRelevanceFilter,
                                  LocationFilter locationFilter,
                                  YoeFilter yoeFilter,
                                  DeduplicationFilter deduplicationFilter) {
        this.jobPostingRepository = jobPostingRepository;
        this.companyRepository = companyRepository;
        this.profileLoader = profileLoader;
        this.languageFilter = languageFilter;
        this.roleRelevanceFilter = roleRelevanceFilter;
        this.locationFilter = locationFilter;
        this.yoeFilter = yoeFilter;
        this.deduplicationFilter = deduplicationFilter;
    }

    /**
     * Search Indeed for jobs matching profile config, filter, and persist new postings.
     *
     * @return int[]{created, filtered, searches}
     */
    public int[] searchAndCreate() {
        PersonalProfile profile = profileLoader.getProfile();
        PersonalProfile.IndeedSearchConfig config = profile.indeedSearch();

        List<String> keywords;
        List<String> locations;
        int resultsWanted;
        int hoursOld;

        if (config != null) {
            keywords = config.keywords();
            locations = config.locations();
            resultsWanted = config.resultsWanted();
            hoursOld = config.hoursOld();
        } else {
            keywords = List.of("backend engineer", "Java developer");
            locations = profile.preferences() != null && profile.preferences().locations() != null
                    ? profile.preferences().locations() : List.of("Germany");
            resultsWanted = 25;
            hoursOld = 24;
        }

        if (keywords.isEmpty() || locations.isEmpty()) {
            log.warn("No keywords or locations for Indeed search, skipping");
            return new int[]{0, 0, 0};
        }

        log.info("Indeed search starting: keywords={}, locations={}", keywords, locations);

        int created = 0, filtered = 0, searched = 0;

        for (String keyword : keywords) {
            for (String location : locations) {
                searched++;
                try {
                    List<IndeedJob> jobs = scrapeIndeed(keyword, location, resultsWanted, hoursOld);
                    int[] stats = processJobs(jobs);
                    created += stats[0];
                    filtered += stats[1];
                } catch (Exception e) {
                    log.error("Indeed search failed for '{}' in '{}': {}", keyword, location, e.getMessage());
                }
            }
        }

        log.info("Indeed search complete: created={}, filtered={}, searches={}", created, filtered, searched);
        return new int[]{created, filtered, searched};
    }

    List<IndeedJob> scrapeIndeed(String keyword, String location, int resultsWanted, int hoursOld) throws Exception {
        File outputFile = File.createTempFile("jobspy-", ".json");
        outputFile.deleteOnExit();

        try {
            List<String> command = new ArrayList<>(List.of(
                    "npx", "-y", "jobspy-js",
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

            boolean finished = process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("jobspy-js process timed out after " + PROCESS_TIMEOUT.toSeconds() + "s");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String stderr = new String(process.getInputStream().readAllBytes());
                throw new RuntimeException("jobspy-js exited with code " + exitCode + ": " + stderr.substring(0, Math.min(stderr.length(), 200)));
            }

            if (!outputFile.exists() || outputFile.length() == 0) {
                return List.of();
            }

            String json = Files.readString(outputFile.toPath());
            return MAPPER.readValue(json, new TypeReference<List<IndeedJob>>() {});
        } finally {
            outputFile.delete();
        }
    }

    int[] processJobs(List<IndeedJob> jobs) {
        int created = 0, filtered = 0;

        if (jobs == null || jobs.isEmpty()) {
            return new int[]{0, 0};
        }

        for (IndeedJob job : jobs) {
            if (job.title() == null || job.title().isBlank()) continue;
            if (job.company() == null || job.company().isBlank()) continue;

            String externalId = extractExternalId(job.id(), job.jobUrl());

            // Skip duplicates
            if (jobPostingRepository.existsBySourceAndExternalId(AtsType.INDEED, externalId)) {
                continue;
            }

            // Apply full filter cascade: language → role → location → yoe → dedup
            FilterDecision decision = FilterDecision.KEEP;
            String filterReason = null;
            Integer requiredYoe = null;
            String fingerprint = deduplicationFilter.generateFingerprint(
                    job.title(), job.company(), job.location());

            if (job.description() != null && !job.description().isBlank()) {
                FilterResult langResult = languageFilter.filter(job.title(), job.description());
                if (langResult.decision() != FilterDecision.KEEP) {
                    decision = FilterDecision.SKIP;
                    filterReason = langResult.reason();
                }
            }

            if (decision == FilterDecision.KEEP) {
                FilterResult roleResult = roleRelevanceFilter.filter(job.title());
                if (roleResult.decision() != FilterDecision.KEEP) {
                    decision = FilterDecision.SKIP;
                    filterReason = roleResult.reason();
                }
            }

            if (decision == FilterDecision.KEEP && job.location() != null) {
                FilterResult locResult = locationFilter.filter(job.location());
                if (locResult.decision() != FilterDecision.KEEP) {
                    decision = FilterDecision.SKIP;
                    filterReason = locResult.reason();
                }
            }

            if (decision == FilterDecision.KEEP && job.description() != null && !job.description().isBlank()) {
                requiredYoe = yoeFilter.extractYoe(job.description());
                FilterResult yoeResult = yoeFilter.filter(requiredYoe);
                if (yoeResult.decision() != FilterDecision.KEEP) {
                    decision = FilterDecision.SKIP;
                    filterReason = yoeResult.reason();
                }
            }

            if (decision == FilterDecision.KEEP) {
                Optional<JobPosting> duplicate = jobPostingRepository
                        .findFirstByFingerprintAndLanguageFilter(fingerprint, FilterDecision.KEEP);
                if (duplicate.isPresent()) {
                    decision = FilterDecision.SKIP;
                    filterReason = "duplicate of " + duplicate.get().getSource();
                }
            }

            if (decision == FilterDecision.SKIP) {
                filtered++;
            }

            String applyUrl = job.jobUrl() != null ? job.jobUrl() : "";
            Company companyEntity = findOrCreateCompany(job.company());
            JobPosting posting = JobPosting.builder()
                    .source(AtsType.INDEED)
                    .externalId(externalId)
                    .title(job.title())
                    .company(companyEntity)
                    .location(job.location())
                    .description(job.description())
                    .applyUrl(applyUrl)
                    .discoveredDate(LocalDate.now())
                    .languageFilter(decision)
                    .filterReason(filterReason)
                    .requiredYoe(requiredYoe)
                    .fingerprint(fingerprint)
                    .externalLinks(Map.of("indeed", applyUrl))
                    .build();
            jobPostingRepository.save(posting);
            created++;
        }

        return new int[]{created, filtered};
    }

    String extractExternalId(String id, String url) {
        // Prefer the structured ID from jobspy-js (e.g., "in-6d3675aae7316bb9")
        if (id != null && !id.isBlank()) {
            return id;
        }
        // Fallback: extract jk param from Indeed URL
        if (url != null) {
            Matcher matcher = JK_PATTERN.matcher(url);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        // Last resort: URL hash
        return Integer.toHexString((url != null ? url : "").hashCode());
    }

    private Company findOrCreateCompany(String name) {
        String normalized = name.toLowerCase().trim();
        return companyRepository.findByNormalizedName(normalized)
                .orElseGet(() -> {
                    Company c = Company.builder()
                            .name(name)
                            .normalizedName(normalized)
                            .isActive(true)
                            .status(CompanyStatus.DISCOVERED)
                            .discoveredVia(DiscoverySource.JOBSPY)
                            .discoveredAt(LocalDateTime.now())
                            .build();
                    return companyRepository.save(c);
                });
    }

    /**
     * JSON structure returned by jobspy-js CLI.
     */
    record IndeedJob(
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
    ) {
        String jobUrl() {
            return job_url;
        }
    }
}
