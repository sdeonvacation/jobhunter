package dev.jobhunter.service;

import dev.jobhunter.dto.FunnelMetricsDto;
import dev.jobhunter.dto.PatternAnalyticsDto;
import dev.jobhunter.dto.PatternAnalyticsDto.BlockerEntry;
import dev.jobhunter.dto.PatternAnalyticsDto.ScoreComparisonDto;
import dev.jobhunter.dto.PatternAnalyticsDto.SkillGapEntry;
import dev.jobhunter.model.Application;
import dev.jobhunter.model.JobOutcome;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.MatchScore;
import dev.jobhunter.model.enums.ApplicationStatus;
import dev.jobhunter.model.enums.OutcomeStage;
import dev.jobhunter.repository.ApplicationRepository;
import dev.jobhunter.repository.JobOutcomeRepository;
import dev.jobhunter.repository.MatchScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PatternAnalysisService {

    private final ApplicationRepository applicationRepository;
    private final JobOutcomeRepository jobOutcomeRepository;
    private final MatchScoreRepository matchScoreRepository;

    private static final Set<ApplicationStatus> POSITIVE_STATUSES = EnumSet.of(
            ApplicationStatus.PHONE_SCREEN,
            ApplicationStatus.INTERVIEWING,
            ApplicationStatus.OFFERED
    );

    private static final Set<ApplicationStatus> NEGATIVE_STATUSES = EnumSet.of(
            ApplicationStatus.REJECTED
    );

    @Transactional(readOnly = true)
    public PatternAnalyticsDto analyzePatterns(LocalDate since) {
        List<Application> applications = applicationRepository.findAll().stream()
                .filter(a -> a.getAppliedDate() != null && !a.getAppliedDate().isBefore(since))
                .toList();

        long totalEvaluated = matchScoreRepository.count();

        FunnelMetricsDto funnel = computeFunnel(totalEvaluated, applications);
        ScoreComparisonDto scoreComparison = computeScoreComparison(applications);
        List<BlockerEntry> blockerAnalysis = computeBlockerAnalysis(applications);
        List<SkillGapEntry> techStackGaps = computeTechStackGaps(applications);
        int scoreThreshold = computeScoreThreshold(applications);
        Map<String, Integer> archetypeByCompany = computeArchetypeByCompany(applications);
        Map<String, Integer> archetypeByRemoteType = computeArchetypeByRemoteType(applications);

        log.info("Pattern analysis completed: {} evaluated, {} applied since {}",
                totalEvaluated, applications.size(), since);

        return new PatternAnalyticsDto(
                funnel,
                scoreComparison,
                blockerAnalysis,
                techStackGaps,
                scoreThreshold,
                archetypeByCompany,
                archetypeByRemoteType
        );
    }

    private FunnelMetricsDto computeFunnel(long totalEvaluated, List<Application> applications) {
        int applied = applications.size();
        int responded = 0;
        int interviewing = 0;
        int offered = 0;
        int rejected = 0;

        for (Application app : applications) {
            switch (app.getStatus()) {
                case PHONE_SCREEN -> responded++;
                case INTERVIEWING -> { responded++; interviewing++; }
                case OFFERED -> { responded++; interviewing++; offered++; }
                case REJECTED -> rejected++;
                default -> {}
            }
        }

        double applicationRate = totalEvaluated > 0 ? (double) applied / totalEvaluated : 0.0;
        double responseRate = applied > 0 ? (double) responded / applied : 0.0;
        double interviewRate = responded > 0 ? (double) interviewing / responded : 0.0;
        double offerRate = interviewing > 0 ? (double) offered / interviewing : 0.0;

        return new FunnelMetricsDto(
                (int) totalEvaluated,
                applied,
                responded,
                interviewing,
                offered,
                rejected,
                round(applicationRate),
                round(responseRate),
                round(interviewRate),
                round(offerRate)
        );
    }

    private ScoreComparisonDto computeScoreComparison(List<Application> applications) {
        List<Integer> positiveScores = new ArrayList<>();
        List<Integer> negativeScores = new ArrayList<>();

        for (Application app : applications) {
            Optional<MatchScore> scoreOpt = matchScoreRepository.findByJobId(app.getJob().getId());
            if (scoreOpt.isEmpty()) continue;

            int score = scoreOpt.get().getOverallScore();
            if (POSITIVE_STATUSES.contains(app.getStatus())) {
                positiveScores.add(score);
            } else if (NEGATIVE_STATUSES.contains(app.getStatus())) {
                negativeScores.add(score);
            }
        }

        double avgPositive = positiveScores.stream()
                .mapToInt(Integer::intValue).average().orElse(0.0);
        double avgNegative = negativeScores.stream()
                .mapToInt(Integer::intValue).average().orElse(0.0);

        return new ScoreComparisonDto(
                round(avgPositive),
                round(avgNegative),
                positiveScores.size(),
                negativeScores.size()
        );
    }

    private List<BlockerEntry> computeBlockerAnalysis(List<Application> applications) {
        Map<String, Integer> reasonCounts = new HashMap<>();

        for (Application app : applications) {
            if (app.getStatus() != ApplicationStatus.REJECTED) continue;

            List<JobOutcome> outcomes = jobOutcomeRepository.findByApplicationId(app.getId());
            for (JobOutcome outcome : outcomes) {
                if (outcome.getStage() == OutcomeStage.REJECTED
                        && outcome.getNotes() != null
                        && !outcome.getNotes().isBlank()) {
                    String reason = outcome.getNotes().trim().toLowerCase();
                    reasonCounts.merge(reason, 1, Integer::sum);
                }
            }
        }

        return reasonCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> new BlockerEntry(e.getKey(), e.getValue()))
                .toList();
    }

    private List<SkillGapEntry> computeTechStackGaps(List<Application> applications) {
        Map<String, Integer> skillCounts = new HashMap<>();

        for (Application app : applications) {
            Optional<MatchScore> scoreOpt = matchScoreRepository.findByJobId(app.getJob().getId());
            if (scoreOpt.isEmpty()) continue;

            List<String> missingSkills = scoreOpt.get().getMissingSkills();
            if (missingSkills == null) continue;

            for (String skill : missingSkills) {
                skillCounts.merge(skill.toLowerCase(), 1, Integer::sum);
            }
        }

        return skillCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(15)
                .map(e -> new SkillGapEntry(e.getKey(), e.getValue()))
                .toList();
    }

    private int computeScoreThreshold(List<Application> applications) {
        return applications.stream()
                .filter(a -> POSITIVE_STATUSES.contains(a.getStatus()))
                .map(a -> matchScoreRepository.findByJobId(a.getJob().getId()))
                .filter(Optional::isPresent)
                .mapToInt(opt -> opt.get().getOverallScore())
                .min()
                .orElse(0);
    }

    private Map<String, Integer> computeArchetypeByCompany(List<Application> applications) {
        return applications.stream()
                .filter(a -> a.getJob() != null && a.getJob().getCompany() != null)
                .collect(Collectors.groupingBy(
                        a -> a.getJob().getCompany().getName(),
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));
    }

    private Map<String, Integer> computeArchetypeByRemoteType(List<Application> applications) {
        return applications.stream()
                .filter(a -> a.getJob() != null)
                .collect(Collectors.groupingBy(
                        a -> a.getJob().getIsRemote() != null
                                ? a.getJob().getIsRemote().name()
                                : "UNKNOWN",
                        Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
                ));
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
