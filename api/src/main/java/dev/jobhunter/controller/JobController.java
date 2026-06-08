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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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

    public JobController(JobPostingRepository jobPostingRepository,
                         JobSkillRepository jobSkillRepository,
                         DailyDigestService dailyDigestService) {
        this.jobPostingRepository = jobPostingRepository;
        this.jobSkillRepository = jobSkillRepository;
        this.dailyDigestService = dailyDigestService;
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

        // Source tab filtering: "ats" excludes LINKEDIN/INDEED, "linkedin"/"indeed" filters to that source
        if ("linkedin".equalsIgnoreCase(source)) {
            if (company != null && !company.isBlank()) {
                jobs = jobPostingRepository.findByIsActiveTrueAndAppliedFalseAndLanguageFilterAndSourceAndCompanyName(
                        FilterDecision.KEEP, JobSource.LINKEDIN, company, pageable);
            } else {
                jobs = jobPostingRepository.findByIsActiveTrueAndAppliedFalseAndLanguageFilterAndSource(
                        FilterDecision.KEEP, JobSource.LINKEDIN, pageable);
            }
        } else if ("indeed".equalsIgnoreCase(source)) {
            if (company != null && !company.isBlank()) {
                jobs = jobPostingRepository.findByIsActiveTrueAndAppliedFalseAndLanguageFilterAndSourceAndCompanyName(
                        FilterDecision.KEEP, JobSource.INDEED, company, pageable);
            } else {
                jobs = jobPostingRepository.findByIsActiveTrueAndAppliedFalseAndLanguageFilterAndSource(
                        FilterDecision.KEEP, JobSource.INDEED, pageable);
            }
        } else if ("ats".equalsIgnoreCase(source) || source == null || source.isBlank()) {
            if (company != null && !company.isBlank()) {
                jobs = jobPostingRepository.findByIsActiveTrueAndAppliedFalseAndLanguageFilterAndSourceNotInAndCompanyName(
                        FilterDecision.KEEP, List.of(JobSource.LINKEDIN, JobSource.INDEED), company, pageable);
            } else {
                jobs = jobPostingRepository.findByIsActiveTrueAndAppliedFalseAndLanguageFilterAndSourceNotIn(
                        FilterDecision.KEEP, List.of(JobSource.LINKEDIN, JobSource.INDEED), pageable);
            }
        } else {
            jobs = jobPostingRepository.findByIsActiveTrueAndAppliedFalseAndLanguageFilter(
                    FilterDecision.KEEP, pageable);
        }

        return jobs.map(DtoMapper::toJobSummary);
    }

    @GetMapping("/applied")
    public Page<JobSummaryDto> getAppliedJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "appliedAt"));
        Page<JobPosting> jobs = jobPostingRepository.findByIsActiveTrueAndAppliedTrue(pageable);
        return jobs.map(DtoMapper::toJobSummary);
    }

    @GetMapping("/today")
    public Page<JobSummaryDto> getTodayJobs(
            @RequestParam(defaultValue = "matchScore") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Sort sortOrder = switch (sort) {
            case "date" -> Sort.by(Sort.Direction.DESC, "discoveredDate");
            default -> Sort.by(Sort.Direction.DESC, "matchScore.overallScore")
                    .and(Sort.by(Sort.Direction.DESC, "discoveredDate"))
                    .and(Sort.by(Sort.Direction.DESC, "opportunityScore.score"));
        };
        Pageable pageable = PageRequest.of(page, size, sortOrder);
        Page<JobPosting> jobs = jobPostingRepository.findByIsActiveTrueAndAppliedFalseAndLanguageFilterAndDiscoveredDate(
                FilterDecision.KEEP, LocalDate.now(), pageable);
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

        // Get top opportunities for today
        Pageable top5 = PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "discoveredDate"));
        Page<JobPosting> topJobs = jobPostingRepository.findByIsActiveTrueAndLanguageFilter(
                FilterDecision.KEEP, top5);

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
        // Top opportunities by score
        Pageable top10 = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "discoveredDate"));
        Page<JobPosting> topJobs = jobPostingRepository.findByIsActiveTrueAndLanguageFilter(
                FilterDecision.KEEP, top10);

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
}
