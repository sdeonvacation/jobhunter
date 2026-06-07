package dev.jobhub.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobhub.filter.DeduplicationFilter;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Searches LinkedIn for jobs matching profile skills, then enriches existing ATS jobs
 * with LinkedIn links or creates new LinkedIn-source job postings.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class LinkedInJobSearchService {

    private final HttpMcpClient httpMcpClient;
    private final LinkedInRateLimiter rateLimiter;
    private final JobPostingRepository jobPostingRepository;
    private final CompanyRepository companyRepository;
    private final PersonalProfileLoader profileLoader;
    private final LanguageFilter languageFilter;
    private final RoleRelevanceFilter roleRelevanceFilter;
    private final LocationFilter locationFilter;
    private final YoeFilter yoeFilter;
    private final DeduplicationFilter deduplicationFilter;

    public LinkedInJobSearchService(HttpMcpClient httpMcpClient,
                                    LinkedInRateLimiter rateLimiter,
                                    JobPostingRepository jobPostingRepository,
                                    CompanyRepository companyRepository,
                                    PersonalProfileLoader profileLoader,
                                    LanguageFilter languageFilter,
                                    RoleRelevanceFilter roleRelevanceFilter,
                                    LocationFilter locationFilter,
                                    YoeFilter yoeFilter,
                                    DeduplicationFilter deduplicationFilter) {
        this.httpMcpClient = httpMcpClient;
        this.rateLimiter = rateLimiter;
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
     * Search LinkedIn for jobs matching profile skills, then:
     * - Match against existing ATS jobs by company name + title similarity
     * - If match found: add "linkedin" entry to external_links
     * - If no match: create new JobPosting with source=LINKEDIN
     *
     * @return int[]{enriched, newlyCreated, searched}
     */
    public int[] searchAndMatch() {
        PersonalProfile profile = profileLoader.getProfile();
        List<String> keywords = extractSearchKeywords(profile);
        List<String> locations = extractLocations(profile);

        log.info("LinkedIn search starting: keywords={}, locations={}", keywords, locations);

        if (keywords.isEmpty() || locations.isEmpty()) {
            log.warn("No keywords or locations found in profile, skipping LinkedIn search");
            return new int[]{0, 0, 0};
        }

        int enriched = 0, created = 0, searched = 0;

        for (String keyword : keywords) {
            for (String location : locations) {
                if (!rateLimiter.acquire(ToolCategory.SEARCH)) {
                    log.warn("Rate limit hit, stopping LinkedIn search");
                    return new int[]{enriched, created, searched};
                }

                searched++;
                Map<String, Object> params = Map.of(
                        "keywords", keyword,
                        "location", location,
                        "date_posted", getDatePosted(profile)
                );

                try {
                    JsonNode result = httpMcpClient.callTool("search_jobs", params);
                    int[] stats = processSearchResults(result);
                    enriched += stats[0];
                    created += stats[1];
                } catch (Exception e) {
                    log.error("LinkedIn search failed for '{}' in '{}': {}", keyword, location, e.getMessage());
                }
            }
        }

        log.info("LinkedIn job search complete: {} enriched, {} created, {} searches", enriched, created, searched);
        return new int[]{enriched, created, searched};
    }

    int[] processSearchResults(JsonNode result) {
        int enriched = 0, created = 0;

        JsonNode sc = result.path("structuredContent");
        JsonNode jobIds = sc.path("job_ids");
        String searchText = sc.path("sections").path("search_results").asText("");

        if (searchText.isBlank() || !jobIds.isArray()) {
            return new int[]{0, 0};
        }

        String[] lines = searchText.split("\n");
        List<ParsedJob> parsedJobs = parseJobs(lines);

        int idIdx = 0;
        for (ParsedJob pj : parsedJobs) {
            if (idIdx >= jobIds.size()) break;

            String jobId = jobIds.get(idIdx).asText();
            String linkedinUrl = "https://www.linkedin.com/jobs/view/" + jobId + "/";
            idIdx++;

            // Skip if this LinkedIn job already exists
            if (jobPostingRepository.existsBySourceAndExternalId(AtsType.LINKEDIN, jobId)) {
                continue;
            }

            // Try enrichment: fingerprint-based matching (most reliable)
            String fingerprint = deduplicationFilter.generateFingerprint(
                    pj.title(), pj.company(), pj.location());
            Optional<JobPosting> atsMatch = jobPostingRepository.findAtsJobByFingerprint(fingerprint);

            if (atsMatch.isEmpty()) {
                // Fallback: company name + title keyword matching
                String normalizedCompany = pj.company().toLowerCase().trim();
                String normalizedCompanyDashed = normalizedCompany.replace(" ", "-");
                String titleKeyword = extractTitleKeyword(pj.title());

                List<JobPosting> existingJobs = jobPostingRepository
                        .findByCompanyNormalizedNameAndTitleContaining(normalizedCompany, titleKeyword);
                if (existingJobs.isEmpty()) {
                    existingJobs = jobPostingRepository
                            .findByCompanyNormalizedNameAndTitleContaining(normalizedCompanyDashed, titleKeyword);
                }
                if (!existingJobs.isEmpty()) {
                    atsMatch = Optional.ofNullable(findBestTitleMatch(existingJobs, pj.title()));
                }
            }

            if (atsMatch.isPresent()) {
                JobPosting best = atsMatch.get();
                Map<String, String> links = best.getExternalLinks() != null
                        ? new HashMap<>(best.getExternalLinks()) : new HashMap<>();
                links.put("linkedin", linkedinUrl);
                best.setExternalLinks(links);
                jobPostingRepository.save(best);
                enriched++;
            } else {
                // Fetch job description from LinkedIn for language filtering + scoring
                String description = fetchJobDescription(jobId);
                FilterDecision langDecision = FilterDecision.KEEP;
                String filterReason = null;
                Integer requiredYoe = null;

                // Full filter cascade: language → role → location → yoe → dedup
                if (description != null && !description.isBlank()) {
                    var filterResult = languageFilter.filter(pj.title(), description);
                    if (filterResult.decision() != FilterDecision.KEEP) {
                        langDecision = FilterDecision.SKIP;
                        filterReason = filterResult.reason();
                    }
                }

                if (langDecision == FilterDecision.KEEP) {
                    var roleResult = roleRelevanceFilter.filter(pj.title());
                    if (roleResult.decision() != FilterDecision.KEEP) {
                        langDecision = FilterDecision.SKIP;
                        filterReason = roleResult.reason();
                    }
                }

                if (langDecision == FilterDecision.KEEP && pj.location() != null) {
                    var locResult = locationFilter.filter(pj.location());
                    if (locResult.decision() != FilterDecision.KEEP) {
                        langDecision = FilterDecision.SKIP;
                        filterReason = locResult.reason();
                    }
                }

                if (langDecision == FilterDecision.KEEP && description != null && !description.isBlank()) {
                    requiredYoe = yoeFilter.extractYoe(description);
                    var yoeResult = yoeFilter.filter(requiredYoe);
                    if (yoeResult.decision() != FilterDecision.KEEP) {
                        langDecision = FilterDecision.SKIP;
                        filterReason = yoeResult.reason();
                    }
                }

                if (langDecision == FilterDecision.KEEP) {
                    var duplicate = jobPostingRepository
                            .findFirstByFingerprintAndLanguageFilter(fingerprint, FilterDecision.KEEP);
                    if (duplicate.isPresent()) {
                        langDecision = FilterDecision.SKIP;
                        filterReason = "duplicate of " + duplicate.get().getSource();
                    }
                }

                Company company = findOrCreateCompany(pj.company());
                JobPosting newJob = JobPosting.builder()
                        .source(AtsType.LINKEDIN)
                        .externalId(jobId)
                        .title(pj.title())
                        .company(company)
                        .location(pj.location())
                        .description(description)
                        .applyUrl(linkedinUrl)
                        .discoveredDate(LocalDate.now())
                        .languageFilter(langDecision)
                        .filterReason(filterReason)
                        .requiredYoe(requiredYoe)
                        .fingerprint(fingerprint)
                        .externalLinks(Map.of("linkedin", linkedinUrl))
                        .build();
                jobPostingRepository.save(newJob);
                created++;
            }
        }

        return new int[]{enriched, created};
    }

    List<ParsedJob> parseJobs(String[] lines) {
        List<ParsedJob> parsedJobs = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (isLocationLine(line) && i >= 2) {
                String company = lines[i - 1].trim();
                int titleIdx = i - 2;
                while (titleIdx >= 0 && lines[titleIdx].trim().endsWith("with verification")) {
                    titleIdx--;
                }
                String title = titleIdx >= 0 ? lines[titleIdx].trim() : "";

                if (!company.isEmpty() && !title.isEmpty()
                        && !company.contains("results") && !company.startsWith("Set alert")
                        && !company.startsWith("Jump to") && !company.endsWith("with verification")) {
                    parsedJobs.add(new ParsedJob(title, company, line));
                }
            }
        }

        return parsedJobs;
    }

    JobPosting findBestTitleMatch(List<JobPosting> candidates, String targetTitle) {
        String lower = targetTitle.toLowerCase();
        for (JobPosting jp : candidates) {
            String candidateTitle = jp.getTitle().toLowerCase();
            if (candidateTitle.contains(lower) || lower.contains(candidateTitle)) {
                return jp;
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    String extractTitleKeyword(String title) {
        String clean = title.replaceAll("\\(.*?\\)", "").trim();
        String[] words = clean.split("\\s+");
        Set<String> stopwords = Set.of("senior", "junior", "staff", "lead", "principal",
                "the", "and", "for", "with");
        return Arrays.stream(words)
                .filter(w -> w.length() > 3)
                .filter(w -> !stopwords.contains(w.toLowerCase()))
                .findFirst()
                .orElse(words.length > 0 ? words[0] : title);
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
                            .discoveredVia(DiscoverySource.LINKEDIN)
                            .discoveredAt(LocalDateTime.now())
                            .build();
                    return companyRepository.save(c);
                });
    }

    private boolean isLocationLine(String line) {
        return line.contains("(Hybrid)") || line.contains("(Remote)")
                || line.contains("(On-site)") || line.contains("(On-Site)")
                || line.contains("(Onsite)");
    }

    List<String> extractSearchKeywords(PersonalProfile profile) {
        if (profile.linkedInSearch() != null && profile.linkedInSearch().query() != null
                && !profile.linkedInSearch().query().isBlank()) {
            return List.of(profile.linkedInSearch().query());
        }
        // Fallback
        return List.of("(software OR java OR backend) AND (developer OR engineer OR associate) NOT graduate NOT lead NOT junior NOT staff NOT principal");
    }

    List<String> extractLocations(PersonalProfile profile) {
        if (profile.linkedInSearch() != null && profile.linkedInSearch().locations() != null
                && !profile.linkedInSearch().locations().isEmpty()) {
            return profile.linkedInSearch().locations();
        }
        if (profile.preferences() != null && profile.preferences().locations() != null
                && !profile.preferences().locations().isEmpty()) {
            return profile.preferences().locations().stream()
                    .limit(3)
                    .toList();
        }
        return List.of("Germany");
    }

    private String getDatePosted(PersonalProfile profile) {
        if (profile.linkedInSearch() != null && profile.linkedInSearch().datePosted() != null
                && !profile.linkedInSearch().datePosted().isBlank()) {
            return profile.linkedInSearch().datePosted();
        }
        return "week";
    }

    private String fetchJobDescription(String jobId) {
        try {
            if (!rateLimiter.acquire(ToolCategory.PROFILE)) {
                log.debug("Rate limit hit for PROFILE, skipping description fetch for job {}", jobId);
                return null;
            }
            JsonNode result = httpMcpClient.callTool("get_job_details", Map.of("job_id", jobId));
            JsonNode sections = result.path("structuredContent").path("sections");
            String text = sections.path("job_posting").asText("");
            if (text.isBlank()) {
                text = sections.path("job_details").asText("");
            }
            return text.isBlank() ? null : text;
        } catch (Exception e) {
            log.debug("Failed to fetch description for job {}: {}", jobId, e.getMessage());
            return null;
        }
    }

    record ParsedJob(String title, String company, String location) {}
}
