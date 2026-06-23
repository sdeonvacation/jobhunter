package dev.jobhunter.service;

import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class ScoringService {

    private static final int BATCH_SIZE = 200;

    private final JobPostingRepository jobPostingRepository;
    private final MatchScoringService matchScoringService;
    private final OpportunityScoringService opportunityScoringService;

    public ScoringService(JobPostingRepository jobPostingRepository,
                          MatchScoringService matchScoringService,
                          OpportunityScoringService opportunityScoringService) {
        this.jobPostingRepository = jobPostingRepository;
        this.matchScoringService = matchScoringService;
        this.opportunityScoringService = opportunityScoringService;
    }

    public void scoreAllUnscored() {
        Instant start = Instant.now();
        int totalMatched = 0;
        int totalOpportunities = 0;
        try {
            Page<JobPosting> page;
            do {
                page = jobPostingRepository.findUnscoredActiveJobs(
                        FilterDecision.KEEP, PageRequest.of(0, BATCH_SIZE));
                List<JobPosting> jobs = page.getContent();
                if (jobs.isEmpty()) break;
                totalMatched += matchScoringService.scoreJobs(jobs);
                totalOpportunities += opportunityScoringService.scoreJobs(jobs);
            } while (page.hasNext());
            log.info("Scoring complete in {}s: matched={}, opportunities={}",
                    Duration.between(start, Instant.now()).toSeconds(), totalMatched, totalOpportunities);
        } catch (Exception e) {
            log.error("Scoring failed", e);
        }
    }

    public void scoreJobsForEndpoint(UUID endpointId) {
        List<JobPosting> jobs = jobPostingRepository.findUnscoredActiveJobsByEndpoint(
                endpointId, FilterDecision.KEEP);
        if (jobs.isEmpty()) return;
        int matched = matchScoringService.scoreJobs(jobs);
        int opportunities = opportunityScoringService.scoreJobs(jobs);
        log.debug("Scored endpoint [{}]: {} jobs, matched={}, opportunities={}",
                endpointId, jobs.size(), matched, opportunities);
    }

    public void scoreJobsForSource(JobSource source) {
        List<JobPosting> jobs = jobPostingRepository.findUnscoredActiveJobsBySource(
                source, FilterDecision.KEEP);
        if (jobs.isEmpty()) return;
        int matched = matchScoringService.scoreJobs(jobs);
        int opportunities = opportunityScoringService.scoreJobs(jobs);
        log.debug("Scored source [{}]: {} jobs, matched={}, opportunities={}",
                source, jobs.size(), matched, opportunities);
    }
}
