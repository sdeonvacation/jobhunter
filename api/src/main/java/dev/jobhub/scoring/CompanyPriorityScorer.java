package dev.jobhub.scoring;

import dev.jobhub.model.Company;
import dev.jobhub.service.PersonalProfile;
import dev.jobhub.service.PersonalProfileLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Computes company priority score (0-100) based on weighted factors.
 * Updates Company.priorityScore for use in opportunity scoring.
 */
@Slf4j
@Component
public class CompanyPriorityScorer {

    private static final double WEIGHT_INTERVIEW_RATE = 0.35;
    private static final double WEIGHT_AVG_MATCH = 0.25;
    private static final double WEIGHT_HIRING_VOLUME = 0.15;
    private static final double WEIGHT_RECENCY = 0.15;
    private static final double WEIGHT_LOCATION = 0.10;

    private static final int NEUTRAL = 50;

    private final PersonalProfileLoader profileLoader;

    public CompanyPriorityScorer(PersonalProfileLoader profileLoader) {
        this.profileLoader = profileLoader;
    }

    public double score(Company company) {
        int interviewRate = computeInterviewRate(company);
        int avgMatch = computeAvgMatchScore(company);
        int hiringVolume = computeHiringVolume(company);
        int recency = computeRecency(company);
        int locationFit = computeLocationFit(company);

        double score = interviewRate * WEIGHT_INTERVIEW_RATE
                + avgMatch * WEIGHT_AVG_MATCH
                + hiringVolume * WEIGHT_HIRING_VOLUME
                + recency * WEIGHT_RECENCY
                + locationFit * WEIGHT_LOCATION;

        return Math.max(0, Math.min(100, score));
    }

    private int computeInterviewRate(Company company) {
        Double rate = company.getInterviewRate();
        if (rate == null || rate == 0.0) return NEUTRAL;
        // Scale: 0.0 → 0, 0.5 → 75, 1.0 → 100
        return (int) Math.round(rate * 100);
    }

    private int computeAvgMatchScore(Company company) {
        Integer avg = company.getAvgMatchScore();
        return avg != null ? avg : NEUTRAL;
    }

    private int computeHiringVolume(Company company) {
        Integer total = company.getTotalApplications();
        if (total == null || total == 0) return NEUTRAL;
        // More active postings = higher score, cap at 100
        if (total >= 20) return 100;
        if (total >= 10) return 80;
        if (total >= 5) return 65;
        return 50;
    }

    private int computeRecency(Company company) {
        LocalDateTime updated = company.getUpdatedAt();
        if (updated == null) return NEUTRAL;
        long daysSince = ChronoUnit.DAYS.between(updated, LocalDateTime.now());
        if (daysSince <= 7) return 100;
        if (daysSince <= 30) return 75;
        if (daysSince <= 90) return 50;
        return 25;
    }

    private int computeLocationFit(Company company) {
        String country = company.getCountry();
        if (country == null || country.isBlank()) return NEUTRAL;

        PersonalProfile profile = profileLoader.getProfile();
        Set<String> preferred = Set.copyOf(profile.preferences().locations());

        for (String loc : preferred) {
            if (loc.equalsIgnoreCase(country)) return 100;
        }

        Set<String> eu = Set.of("Germany", "France", "Netherlands", "Spain", "Italy",
                "Austria", "Belgium", "Ireland", "Sweden", "Denmark");
        if (eu.contains(country)) return 70;
        return 30;
    }
}
