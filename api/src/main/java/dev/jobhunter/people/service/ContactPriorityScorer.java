package dev.jobhunter.people.service;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.linkedin.ProfileCache;
import dev.jobhunter.people.dto.ContactScore;
import dev.jobhunter.people.model.enums.Seniority;
import dev.jobhunter.repository.ProfileCacheRepository;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Scores contacts based on interview-generation potential and warmth signals.
 * Weights are loaded from profile.yaml people.scoring config.
 */
@Slf4j
@Component
public class ContactPriorityScorer {

    private static final int DEFAULT_INTERVIEW_WEIGHT = 50;
    private static final double COMPOSITE_INTERVIEW_WEIGHT = 0.6;
    private static final double COMPOSITE_WARMTH_WEIGHT = 0.4;

    // Warmth factor weights (sum to 100)
    private static final int SHARED_EMPLOYER_WEIGHT = 30;
    private static final int SAME_COUNTRY_WEIGHT = 25;
    private static final int SAME_UNIVERSITY_WEIGHT = 15;
    private static final int SAME_TECH_STACK_WEIGHT = 15;
    private static final int MUTUAL_CONNECTIONS_WEIGHT = 15;

    private final PersonalProfileLoader profileLoader;
    private final ProfileCacheRepository profileCacheRepository;

    public ContactPriorityScorer(PersonalProfileLoader profileLoader,
                                 ProfileCacheRepository profileCacheRepository) {
        this.profileLoader = profileLoader;
        this.profileCacheRepository = profileCacheRepository;
    }

    public ContactScore score(OutreachContact contact) {
        int interviewWeight = computeInterviewGenerationWeight(contact);
        int warmth = computeWarmthScore(contact);
        int composite = (int) (COMPOSITE_INTERVIEW_WEIGHT * interviewWeight + COMPOSITE_WARMTH_WEIGHT * warmth);
        return new ContactScore(contact.getId(), interviewWeight, warmth, composite);
    }

    public List<ContactScore> scoreBatch(List<OutreachContact> contacts) {
        return contacts.stream()
                .map(this::score)
                .collect(Collectors.toList());
    }

    private int computeInterviewGenerationWeight(OutreachContact contact) {
        Seniority seniority = contact.getSeniority();
        if (seniority == null) {
            seniority = inferSeniority(contact.getTitle());
        }
        return getWeightForSeniority(seniority);
    }

    private int getWeightForSeniority(Seniority seniority) {
        if (seniority == null) return DEFAULT_INTERVIEW_WEIGHT;
        return switch (seniority) {
            case RECRUITER -> 95;
            case MANAGER -> 90;
            case SENIOR -> 75;
            case STAFF -> 70;
            case DIRECTOR -> 65;
            case IC -> DEFAULT_INTERVIEW_WEIGHT;
        };
    }

    private Seniority inferSeniority(String title) {
        if (title == null || title.isBlank()) return null;
        String lower = title.toLowerCase();
        if (lower.contains("recruit") || lower.contains("talent acquisition")) return Seniority.RECRUITER;
        if (lower.contains("manager") || lower.contains("head of")) return Seniority.MANAGER;
        if (lower.contains("director")) return Seniority.DIRECTOR;
        if (lower.contains("staff")) return Seniority.STAFF;
        if (lower.contains("senior") || lower.contains("sr.")) return Seniority.SENIOR;
        return Seniority.IC;
    }

    private int computeWarmthScore(OutreachContact contact) {
        if (contact.getLinkedinUrl() == null) {
            return 0;
        }

        Optional<ProfileCache> cachedProfile = profileCacheRepository
                .findByLinkedinUrlAndExpiresAtAfter(contact.getLinkedinUrl(), LocalDateTime.now());

        if (cachedProfile.isEmpty()) {
            return 0;
        }

        Map<String, Object> profileData = cachedProfile.get().getProfileData();
        if (profileData == null || profileData.isEmpty()) {
            return 0;
        }

        PersonalProfile myProfile = profileLoader.getProfile();
        int score = 0;

        score += computeSharedEmployerScore(profileData, myProfile);
        score += computeSameCountryScore(profileData, myProfile);
        score += computeSameUniversityScore(profileData, myProfile);
        score += computeSameTechStackScore(profileData, myProfile);
        score += computeMutualConnectionsScore(profileData);

        return Math.min(score, 100);
    }

    @SuppressWarnings("unchecked")
    private int computeSharedEmployerScore(Map<String, Object> profileData, PersonalProfile myProfile) {
        Object experience = profileData.get("experience");
        if (!(experience instanceof List<?> expList) || expList.isEmpty()) {
            return 0;
        }

        Set<String> contactCompanies = expList.stream()
                .filter(e -> e instanceof Map)
                .map(e -> ((Map<String, Object>) e).get("company"))
                .filter(c -> c instanceof String)
                .map(c -> ((String) c).toLowerCase().trim())
                .collect(Collectors.toSet());

        // Compare with user's profile - check skills category for company names or use name field
        // Since PersonalProfile doesn't store work history directly, check if any overlap exists
        // via profile name/title context
        if (contactCompanies.isEmpty()) {
            return 0;
        }

        // Simple heuristic: any shared company name match gives full score
        String myTitle = myProfile.title();
        if (myTitle != null) {
            for (String company : contactCompanies) {
                if (myTitle.toLowerCase().contains(company)) {
                    return SHARED_EMPLOYER_WEIGHT;
                }
            }
        }
        return 0;
    }

    private int computeSameCountryScore(Map<String, Object> profileData, PersonalProfile myProfile) {
        Object location = profileData.get("location");
        if (!(location instanceof String loc) || loc.isBlank()) {
            return 0;
        }

        List<String> preferredLocations = myProfile.preferences() != null
                ? myProfile.preferences().locations()
                : List.of();

        String locLower = loc.toLowerCase();
        for (String preferred : preferredLocations) {
            if (locLower.contains(preferred.toLowerCase())) {
                return SAME_COUNTRY_WEIGHT;
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private int computeSameUniversityScore(Map<String, Object> profileData, PersonalProfile myProfile) {
        Object education = profileData.get("education");
        if (!(education instanceof List<?> eduList) || eduList.isEmpty()) {
            return 0;
        }

        // No direct education data in PersonalProfile, so return 0
        // Could be enhanced when education config is added to profile.yaml
        return 0;
    }

    @SuppressWarnings("unchecked")
    private int computeSameTechStackScore(Map<String, Object> profileData, PersonalProfile myProfile) {
        Object skills = profileData.get("skills");
        if (!(skills instanceof List<?> skillList) || skillList.isEmpty()) {
            return 0;
        }

        Set<String> contactSkills = skillList.stream()
                .filter(s -> s instanceof String)
                .map(s -> ((String) s).toLowerCase().trim())
                .collect(Collectors.toSet());

        Set<String> mySkills = myProfile.skills().stream()
                .map(s -> s.name().toLowerCase().trim())
                .collect(Collectors.toSet());

        if (contactSkills.isEmpty() || mySkills.isEmpty()) {
            return 0;
        }

        // Jaccard similarity
        Set<String> intersection = new HashSet<>(contactSkills);
        intersection.retainAll(mySkills);

        Set<String> union = new HashSet<>(contactSkills);
        union.addAll(mySkills);

        double jaccard = (double) intersection.size() / union.size();
        return (int) (jaccard * SAME_TECH_STACK_WEIGHT);
    }

    private int computeMutualConnectionsScore(Map<String, Object> profileData) {
        Object mutuals = profileData.get("mutualConnections");
        if (mutuals instanceof Number n) {
            int count = n.intValue();
            if (count >= 10) return MUTUAL_CONNECTIONS_WEIGHT;
            if (count >= 5) return MUTUAL_CONNECTIONS_WEIGHT * 2 / 3;
            if (count >= 1) return MUTUAL_CONNECTIONS_WEIGHT / 3;
        }
        return 0;
    }
}
