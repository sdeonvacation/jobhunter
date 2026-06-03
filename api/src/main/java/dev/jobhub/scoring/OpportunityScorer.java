package dev.jobhub.scoring;

import dev.jobhub.model.Company;
import dev.jobhub.model.JobPosting;
import dev.jobhub.model.MatchScore;
import dev.jobhub.service.PersonalProfile;
import dev.jobhub.service.PersonalProfileLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Computes composite opportunity score (0-100) from multiple weighted factors.
 * Cold-start: all unknowns default to 50 (neutral).
 */
@Slf4j
@Component
public class OpportunityScorer {

    private static final double WEIGHT_MATCH = 0.30;
    private static final double WEIGHT_INTERVIEW = 0.20;
    private static final double WEIGHT_SALARY = 0.20;
    private static final double WEIGHT_COMPANY = 0.15;
    private static final double WEIGHT_SENIORITY = 0.10;
    private static final double WEIGHT_LOCATION = 0.05;

    private static final int NEUTRAL = 50;

    private static final Set<String> HIGH_SENIORITY = Set.of("staff", "principal", "director", "vp", "head");
    private static final Set<String> SENIOR = Set.of("senior", "sr", "lead");
    private static final Set<String> JUNIOR = Set.of("junior", "jr", "entry", "intern", "graduate");

    private final PersonalProfileLoader profileLoader;

    public OpportunityScorer(PersonalProfileLoader profileLoader) {
        this.profileLoader = profileLoader;
    }

    public OpportunityResult score(JobPosting job, MatchScore matchScore) {
        int matchFactor = matchScore != null ? matchScore.getOverallScore() : NEUTRAL;
        int interviewFactor = computeInterviewFactor(job.getCompany());
        int salaryFactor = computeSalaryFactor(job);
        int companyFactor = computeCompanyFactor(job.getCompany());
        int seniorityFactor = computeSeniorityFactor(job.getTitle());
        int locationFactor = computeLocationFactor(job);

        double score = matchFactor * WEIGHT_MATCH
                + interviewFactor * WEIGHT_INTERVIEW
                + salaryFactor * WEIGHT_SALARY
                + companyFactor * WEIGHT_COMPANY
                + seniorityFactor * WEIGHT_SENIORITY
                + locationFactor * WEIGHT_LOCATION;

        int finalScore = (int) Math.round(score);
        finalScore = Math.max(0, Math.min(100, finalScore));

        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("matchScore", matchFactor);
        breakdown.put("interviewHistory", interviewFactor);
        breakdown.put("salary", salaryFactor);
        breakdown.put("companyQuality", companyFactor);
        breakdown.put("seniority", seniorityFactor);
        breakdown.put("locationFit", locationFactor);

        return new OpportunityResult(finalScore, breakdown);
    }

    private int computeInterviewFactor(Company company) {
        if (company == null) return NEUTRAL;
        Double rate = company.getInterviewRate();
        if (rate == null || rate == 0.0) return NEUTRAL;
        // Scale: 0% → 20, 50% → 70, 100% → 100
        return (int) Math.round(20 + rate * 80);
    }

    private int computeSalaryFactor(JobPosting job) {
        PersonalProfile profile = profileLoader.getProfile();
        int target = profile.preferences().minSalaryEur();
        if (target <= 0) return NEUTRAL;

        BigDecimal salaryMax = job.getSalaryMax();
        BigDecimal salaryMin = job.getSalaryMin();
        if (salaryMax == null && salaryMin == null) return NEUTRAL;

        BigDecimal offered = salaryMax != null ? salaryMax : salaryMin;
        // Compare offered vs target
        double ratio = offered.doubleValue() / target;
        if (ratio >= 1.3) return 100;
        if (ratio >= 1.0) return 80;
        if (ratio >= 0.85) return 60;
        if (ratio >= 0.7) return 40;
        return 20;
    }

    private int computeCompanyFactor(Company company) {
        if (company == null) return NEUTRAL;
        Double priority = company.getPriorityScore();
        return priority != null ? (int) Math.round(priority) : NEUTRAL;
    }

    private int computeSeniorityFactor(String title) {
        if (title == null || title.isBlank()) return NEUTRAL;
        String lower = title.toLowerCase();
        for (String term : HIGH_SENIORITY) {
            if (lower.contains(term)) return 90;
        }
        for (String term : SENIOR) {
            if (lower.contains(term)) return 75;
        }
        for (String term : JUNIOR) {
            if (lower.contains(term)) return 40;
        }
        return 60; // mid-level default
    }

    private int computeLocationFactor(JobPosting job) {
        PersonalProfile profile = profileLoader.getProfile();
        Set<String> preferred = Set.copyOf(profile.preferences().locations());

        // Remote/hybrid is good
        if (job.getIsRemote() != null && job.getIsRemote() != dev.jobhub.model.enums.RemoteType.ONSITE) {
            return 100;
        }

        String country = job.getLocationCountry();
        if (country == null || country.isBlank()) return NEUTRAL;

        String countryLower = country.toLowerCase();
        for (String loc : preferred) {
            if (loc.equalsIgnoreCase(country) || countryLower.contains(loc.toLowerCase())) {
                return 100;
            }
        }

        // EU countries get partial credit
        if (isEuCountry(countryLower)) return 70;
        return 30;
    }

    private boolean isEuCountry(String country) {
        Set<String> eu = Set.of("germany", "france", "netherlands", "spain", "italy",
                "austria", "belgium", "ireland", "sweden", "denmark", "finland",
                "poland", "portugal", "czech republic", "romania", "luxembourg");
        return eu.contains(country);
    }

    public record OpportunityResult(int score, Map<String, Integer> breakdown) {
    }
}
