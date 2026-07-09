package dev.jobhunter.service;

import dev.jobhunter.filter.DescriptionFilterChain;
import dev.jobhunter.ingestion.DescriptionBackfiller;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.strategy.ats.SmartRecruitersStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Backfills descriptions for SmartRecruiters KEEP jobs ingested without one,
 * then re-runs all description-dependent filters via DescriptionFilterChain.
 */
@Slf4j
@Service
public class SmartRecruitersDescriptionBackfiller extends DescriptionBackfiller {

    private final JobPostingRepository jobPostingRepository;
    private final SmartRecruitersStrategy smartRecruitersStrategy;
    private final DescriptionFilterChain descriptionFilterChain;

    public SmartRecruitersDescriptionBackfiller(JobPostingRepository jobPostingRepository,
                                                SmartRecruitersStrategy smartRecruitersStrategy,
                                                DescriptionFilterChain descriptionFilterChain,
                                                MatchScoringService matchScoringService) {
        super(matchScoringService);
        this.jobPostingRepository = jobPostingRepository;
        this.smartRecruitersStrategy = smartRecruitersStrategy;
        this.descriptionFilterChain = descriptionFilterChain;
    }

    @Override
    protected List<JobPosting> doBackfill() {
        List<JobPosting> jobs = jobPostingRepository
                .findBySourceAndLanguageFilterAndDescriptionIsNull(JobSource.SMARTRECRUITERS, FilterDecision.KEEP);

        if (jobs.isEmpty()) return List.of();

        log.info("Backfilling descriptions for {} SmartRecruiters KEEP jobs", jobs.size());
        List<JobPosting> filled = new ArrayList<>();

        for (JobPosting job : jobs) {
            String slug = job.getEndpoint() != null ? job.getEndpoint().getAtsSlug() : null;
            if (slug == null) continue;

            String description = smartRecruitersStrategy.fetchDescription(slug, job.getExternalId());
            if (description != null) {
                job.setDescription(description);
                descriptionFilterChain.refilter(job);
                jobPostingRepository.save(job);
                filled.add(job);
            }
        }

        log.info("SmartRecruiters backfill: {}/{} descriptions filled", filled.size(), jobs.size());
        return filled;
    }
}
