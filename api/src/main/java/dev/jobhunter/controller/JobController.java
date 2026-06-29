package dev.jobhunter.controller;

import dev.jobhunter.dto.*;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.JobSkill;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.JobSkillRepository;
import dev.jobhunter.repository.MatchScoreRepository;
import dev.jobhunter.repository.OpportunityScoreRepository;
import dev.jobhunter.service.DailyDigestService;
import dev.jobhunter.service.DailyDigestService.DigestSnapshot;
import dev.jobhunter.service.FollowUpCadenceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobPostingRepository jobPostingRepository;
    private final JobSkillRepository jobSkillRepository;
    private final DailyDigestService dailyDigestService;
    private final FollowUpCadenceService followUpCadenceService;

    public JobController(JobPostingRepository jobPostingRepository,
                         JobSkillRepository jobSkillRepository,
                         DailyDigestService dailyDigestService,
                         FollowUpCadenceService followUpCadenceService) {
        this.jobPostingRepository = jobPostingRepository;
        this.jobSkillRepository = jobSkillRepository;
        this.dailyDigestService = dailyDigestService;
        this.followUpCadenceService = followUpCadenceService;
    }

    @GetMapping
    public Page<JobSummaryDto> searchJobs(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) Integer minScore,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "matchScore") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Sort sortOrder = switch (sort) {
            case "date" -> Sort.by(Sort.Direction.DESC, "discoveredDate");
            default -> Sort.by(Sort.Direction.DESC, "matchScore.overallScore")
                    .and(Sort.by(Sort.Direction.DESC, "discoveredDate"))
                    .and(Sort.by(Sort.Direction.DESC, "opportunityScore.score"));
        };

        Pageable pageable = PageRequest.of(page, size, sortOrder);

        Page<JobPosting> jobs;

        boolean hasQuery = query != null && !query.isBlank();
        // Resolve source to a specific JobSource enum (for aggregator tabs) or null (for ATS tab)
        JobSource resolvedSource = resolveSource(source);

        if (hasQuery) {
            if (resolvedSource != null) {
                jobs = jobPostingRepository.searchByQueryAndSource(
                        FilterDecision.KEEP, resolvedSource, query.trim(), company, pageable);
            } else {
                jobs = jobPostingRepository.searchByQuery(
                        FilterDecision.KEEP, JobSource.aggregators(), query.trim(), company, pageable);
            }
        } else {
            if (resolvedSource != null) {
                if (company != null && !company.isBlank()) {
                    jobs = jobPostingRepository.findByIsActiveTrueAndAppliedFalseAndHiddenFalseAndLanguageFilterAndSourceAndCompanyName(
                            FilterDecision.KEEP, resolvedSource, company, pageable);
                } else {
                    jobs = jobPostingRepository.findByIsActiveTrueAndAppliedFalseAndHiddenFalseAndLanguageFilterAndSource(
                            FilterDecision.KEEP, resolvedSource, pageable);
                }
            } else {
                // ATS tab: exclude all aggregator sources
                if (company != null && !company.isBlank()) {
                    jobs = jobPostingRepository.findByIsActiveTrueAndAppliedFalseAndHiddenFalseAndLanguageFilterAndSourceNotInAndCompanyName(
                            FilterDecision.KEEP, JobSource.aggregators(), company, pageable);
                } else {
                    jobs = jobPostingRepository.findByIsActiveTrueAndAppliedFalseAndHiddenFalseAndLanguageFilterAndSourceNotIn(
                            FilterDecision.KEEP, JobSource.aggregators(), pageable);
                }
            }
        }

        return jobs.map(DtoMapper::toJobSummary);
    }

    @GetMapping("/applied")
    public Page<JobSummaryDto> getAppliedJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "appliedAt") String sort,
            @RequestParam(required = false) String search) {
        Sort sortOrder = switch (sort) {
            case "matchScore"  -> Sort.by(new Sort.Order(Sort.Direction.DESC, "matchScore.overallScore", Sort.NullHandling.NULLS_LAST));
            case "opportunity" -> Sort.by(new Sort.Order(Sort.Direction.DESC, "opportunityScore.score", Sort.NullHandling.NULLS_LAST));
            case "company"     -> Sort.by(Sort.Direction.ASC, "company.name");
            case "title"       -> Sort.by(Sort.Direction.ASC, "title");
            case "date"        -> Sort.by(Sort.Direction.DESC, "postedDate");
            default            -> Sort.by(Sort.Direction.DESC, "appliedAt");
        };
        Pageable pageable = PageRequest.of(page, size, sortOrder);
        String query = (search != null && !search.isBlank()) ? search.trim() : null;
        Page<JobPosting> jobs = jobPostingRepository.searchApplied(query, pageable);
        return jobs.map(DtoMapper::toJobSummary);
    }

    @GetMapping("/today")
    public Page<JobSummaryDto> getTodayJobs(
            @RequestParam(defaultValue = "matchScore") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Sort sortOrder = switch (sort) {
            case "date" -> Sort.by(Sort.Direction.DESC, "postedDate");
            default -> Sort.by(new Sort.Order(Sort.Direction.DESC, "matchScore.overallScore", Sort.NullHandling.NULLS_LAST))
                    .and(Sort.by(new Sort.Order(Sort.Direction.DESC, "postedDate", Sort.NullHandling.NULLS_LAST)))
                    .and(Sort.by(new Sort.Order(Sort.Direction.DESC, "opportunityScore.score", Sort.NullHandling.NULLS_LAST)));
        };
        Pageable pageable = PageRequest.of(page, size, sortOrder);
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Page<JobPosting> jobs = jobPostingRepository.findRecentlyPostedJobs(
                FilterDecision.KEEP, yesterday, LocalDate.now(), JobSource.aggregators(), pageable);
        return jobs.map(DtoMapper::toJobSummary);
    }

    @GetMapping("/companies")
    public List<String> getCompaniesWithJobs() {
        return jobPostingRepository.findDistinctCompanyNamesWithVisibleJobs(FilterDecision.KEEP);
    }

    @GetMapping("/resolve/{prefix}")
    public ResponseEntity<Map<String, String>> resolveId(@PathVariable String prefix) {
        if (prefix.length() < 6 || prefix.length() > 36) {
            return ResponseEntity.badRequest().build();
        }
        if (prefix.length() == 36) {
            try {
                UUID id = UUID.fromString(prefix);
                if (jobPostingRepository.existsById(id)) {
                    return ResponseEntity.ok(Map.of("id", id.toString()));
                }
                return ResponseEntity.notFound().build();
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        Optional<UUID> found = jobPostingRepository.findIdByPrefix(prefix);
        return found.map(id -> ResponseEntity.ok(Map.of("id", id.toString())))
                   .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/by-url")
    public ResponseEntity<JobDetailDto> getJobByUrl(@RequestParam String url) {
        Optional<JobPosting> jobOpt = jobPostingRepository.findFirstByApplyUrl(url);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        JobPosting job = jobOpt.get();
        List<JobSkill> skills = jobSkillRepository.findByJobId(job.getId());
        return ResponseEntity.ok(DtoMapper.toJobDetail(job, skills));
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobDetailDto> getJob(@PathVariable UUID id) {
        Optional<JobPosting> jobOpt = jobPostingRepository.findById(id);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        JobPosting job = jobOpt.get();
        List<JobSkill> skills = jobSkillRepository.findByJobId(id);
        return ResponseEntity.ok(DtoMapper.toJobDetail(job, skills));
    }

    @PatchMapping("/{id}/applied")
    public ResponseEntity<Void> markApplied(@PathVariable UUID id, @RequestBody(required = false) Map<String, Boolean> body) {
        Optional<JobPosting> jobOpt = jobPostingRepository.findById(id);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        JobPosting job = jobOpt.get();
        boolean applied = body == null || body.getOrDefault("applied", true);
        job.setApplied(applied);
        job.setAppliedAt(applied ? java.time.LocalDateTime.now() : null);
        jobPostingRepository.save(job);
        if (applied) {
            try {
                followUpCadenceService.scheduleFollowUp(id);
            } catch (Exception ignored) {
                // Follow-up creation failure shouldn't block the apply action
            }
        }
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/hidden")
    public ResponseEntity<Void> hideJob(@PathVariable UUID id, @RequestBody(required = false) Map<String, Boolean> body) {
        Optional<JobPosting> jobOpt = jobPostingRepository.findById(id);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        JobPosting job = jobOpt.get();
        boolean hidden = body == null || body.getOrDefault("hidden", true);
        job.setHidden(hidden);
        jobPostingRepository.save(job);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/tech-stack")
    public ResponseEntity<TechStackDto> getTechStack(@PathVariable UUID id) {
        if (!jobPostingRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        List<JobSkill> skills = jobSkillRepository.findByJobId(id);
        return ResponseEntity.ok(DtoMapper.toTechStack(skills));
    }

    @GetMapping("/{id}/score")
    public ResponseEntity<ScoreBreakdownDto> getScore(@PathVariable UUID id) {
        Optional<JobPosting> jobOpt = jobPostingRepository.findById(id);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(DtoMapper.toScoreBreakdown(jobOpt.get()));
    }

    @GetMapping("/daily-digest")
    public ResponseEntity<DailyDigestDto> getDailyDigest() {
        DigestSnapshot snapshot = dailyDigestService.computeDigest();

         // Get top opportunities for today: jobs posted/discovered recently.
         // Aggregator jobs (LinkedIn, Indeed, etc.) require explicit postedDate — discoveredDate
         // is always "today" for these and would surface old posts as new.
         LocalDate yesterday = LocalDate.now().minusDays(1);
         Pageable top5 = PageRequest.of(0, 5,
                 Sort.by(new Sort.Order(Sort.Direction.DESC, "matchScore.overallScore", Sort.NullHandling.NULLS_LAST))
                         .and(Sort.by(new Sort.Order(Sort.Direction.DESC, "opportunityScore.score", Sort.NullHandling.NULLS_LAST))));
         Page<JobPosting> topJobs = jobPostingRepository.findRecentlyPostedJobs(
                 FilterDecision.KEEP, yesterday, LocalDate.now(), JobSource.aggregators(), top5);

        List<JobSummaryDto> topOpportunities = topJobs.getContent().stream()
                .map(DtoMapper::toJobSummary)
                .toList();

        DailyDigestDto digest = new DailyDigestDto(
                snapshot.date(),
                snapshot.newJobsCount(),
                topOpportunities,
                0, // activeApplications - computed separately if needed
                0  // companiesMonitored - computed separately if needed
        );

        return ResponseEntity.ok(digest);
    }

    @GetMapping("/radar")
    public ResponseEntity<RadarDto> getRadar() {
        // Top recent opportunities sorted by match score
        LocalDate twoDaysAgo = LocalDate.now().minusDays(2);
        Pageable top10 = PageRequest.of(0, 10,
                Sort.by(new Sort.Order(Sort.Direction.DESC, "matchScore.overallScore", Sort.NullHandling.NULLS_LAST))
                        .and(Sort.by(new Sort.Order(Sort.Direction.DESC, "opportunityScore.score", Sort.NullHandling.NULLS_LAST))));
        Page<JobPosting> topJobs = jobPostingRepository.findRecentlyPostedJobs(
                FilterDecision.KEEP, twoDaysAgo, LocalDate.now(), JobSource.aggregators(), top10);

        List<JobSummaryDto> topOpportunities = topJobs.getContent().stream()
                .map(DtoMapper::toJobSummary)
                .toList();

        // Heating/cooling trends would require aggregation queries
        RadarDto radar = new RadarDto(
                topOpportunities,
                List.of(),
                List.of()
        );

        return ResponseEntity.ok(radar);
    }

    /**
     * Resolves source tab string to a JobSource enum.
     * Returns null for "ats" or empty (meaning: show only non-aggregator jobs).
     * Returns a specific JobSource for aggregator tab names.
     */
    private JobSource resolveSource(String source) {
        if (source == null || source.isBlank() || "ats".equalsIgnoreCase(source)) {
            return null;
        }
        try {
            JobSource resolved = JobSource.valueOf(source.toUpperCase());
            return resolved.isAggregator() ? resolved : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
