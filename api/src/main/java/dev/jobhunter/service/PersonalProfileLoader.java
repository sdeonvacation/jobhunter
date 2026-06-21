package dev.jobhunter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads personal profile from profile.yaml and exposes it as a Spring bean.
 */
@Slf4j
@Component
public class PersonalProfileLoader {

    @Value("${profile.path:file:./profile.yaml}")
    private Resource profileResource;

    private PersonalProfile profile;

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void load() {
        try (InputStream is = profileResource.getInputStream()) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(is);
            this.profile = parseProfile(data);
            log.info("Personal profile loaded: {} with {} skills",
                    profile.name(), profile.skills().size());
        } catch (IOException e) {
            log.warn("Could not load profile.yaml, using empty profile: {}", e.getMessage());
            this.profile = emptyProfile();
        }
    }

    public PersonalProfile getProfile() {
        return profile;
    }

    @SuppressWarnings("unchecked")
    private PersonalProfile parseProfile(Map<String, Object> data) {
        String name = (String) data.getOrDefault("name", "");
        String title = (String) data.getOrDefault("title", "");
        int years = data.containsKey("years-of-experience")
                ? ((Number) data.get("years-of-experience")).intValue() : 0;

        List<PersonalProfile.ProfileSkill> skills = new ArrayList<>();
        List<Map<String, Object>> skillList = (List<Map<String, Object>>) data.getOrDefault("skills", List.of());
        for (Map<String, Object> skillMap : skillList) {
            skills.add(new PersonalProfile.ProfileSkill(
                    (String) skillMap.getOrDefault("name", ""),
                    (String) skillMap.getOrDefault("proficiency", "intermediate"),
                    (String) skillMap.getOrDefault("category", "")
            ));
        }

        Map<String, Object> prefs = (Map<String, Object>) data.getOrDefault("preferences", Map.of());
        PersonalProfile.Preferences preferences = new PersonalProfile.Preferences(
                (List<String>) prefs.getOrDefault("locations", List.of()),
                (String) prefs.getOrDefault("employment-type", "FULL_TIME"),
                prefs.containsKey("min-salary-eur") ? ((Number) prefs.get("min-salary-eur")).intValue() : 0,
                (List<String>) prefs.getOrDefault("seniority", List.of()),
                (List<String>) prefs.getOrDefault("languages", List.of()),
                (List<String>) prefs.getOrDefault("excluded-industries", List.of())
        );

        PersonalProfile.FilterConfig filters = parseFilters(
                (Map<String, Object>) data.getOrDefault("filters", null));
        PersonalProfile.ScoringConfig scoring = parseScoring(
                (Map<String, Object>) data.getOrDefault("scoring", null));
        PersonalProfile.LinkedInSearchConfig linkedInSearch = parseLinkedInSearch(
                (Map<String, Object>) data.getOrDefault("linkedin-search", null));
        PersonalProfile.IndeedSearchConfig indeedSearch = parseIndeedSearch(
                (Map<String, Object>) data.getOrDefault("indeed-search", null));

        return new PersonalProfile(name, title, years, skills, preferences, filters, scoring, linkedInSearch, indeedSearch);
    }

    @SuppressWarnings("unchecked")
    private PersonalProfile.FilterConfig parseFilters(Map<String, Object> filtersMap) {
        if (filtersMap == null) return null;

        PersonalProfile.RoleFilterConfig role = null;
        PersonalProfile.LocationFilterConfig location = null;
        PersonalProfile.YoeFilterConfig yoe = null;
        PersonalProfile.LanguageFilterConfig language = null;

        Map<String, Object> roleMap = (Map<String, Object>) filtersMap.get("role");
        if (roleMap != null) {
            role = new PersonalProfile.RoleFilterConfig(
                    (List<String>) roleMap.getOrDefault("include-patterns", List.of()),
                    (List<String>) roleMap.getOrDefault("exclude-keywords", List.of())
            );
        }

        Map<String, Object> locationMap = (Map<String, Object>) filtersMap.get("location");
        if (locationMap != null) {
            // Backward compat: read "target-cities" first, fall back to deprecated "germany-cities"
            List<String> cities = (List<String>) locationMap.get("target-cities");
            if (cities == null) {
                cities = (List<String>) locationMap.get("germany-cities");
                if (cities != null) {
                    log.warn("Config key 'filters.location.germany-cities' is deprecated, use 'target-cities' instead");
                }
            }
            location = new PersonalProfile.LocationFilterConfig(
                    cities != null ? cities : List.of(),
                    (List<String>) locationMap.getOrDefault("remote-patterns", List.of())
            );
        }

        Map<String, Object> yoeMap = (Map<String, Object>) filtersMap.get("yoe");
        if (yoeMap != null) {
            int maxYears = yoeMap.containsKey("max-years")
                    ? ((Number) yoeMap.get("max-years")).intValue() : 5;
            yoe = new PersonalProfile.YoeFilterConfig(maxYears);
        }

        Map<String, Object> languageMap = (Map<String, Object>) filtersMap.get("language");
        if (languageMap != null) {
            String target = (String) languageMap.getOrDefault("target", "en");
            List<String> excludePatterns = (List<String>) languageMap.getOrDefault("exclude-patterns", List.of());
            language = new PersonalProfile.LanguageFilterConfig(target, excludePatterns);
        }

        PersonalProfile.VisaSponsorshipFilterConfig visaSponsorship = null;
        Map<String, Object> visaMap = (Map<String, Object>) filtersMap.get("visa-sponsorship");
        if (visaMap != null) {
            PersonalProfile.AiFallbackConfig aiFallback = null;
            Map<String, Object> aiMap = (Map<String, Object>) visaMap.get("ai-fallback");
            if (aiMap != null) {
                aiFallback = new PersonalProfile.AiFallbackConfig(
                        Boolean.TRUE.equals(aiMap.get("enabled")),
                        aiMap.containsKey("max-description-chars")
                                ? ((Number) aiMap.get("max-description-chars")).intValue() : 4000,
                        aiMap.containsKey("daily-limit")
                                ? ((Number) aiMap.get("daily-limit")).intValue() : 50
                );
            }
            visaSponsorship = new PersonalProfile.VisaSponsorshipFilterConfig(
                    (List<String>) visaMap.getOrDefault("target-countries", List.of()),
                    (List<String>) visaMap.getOrDefault("de-patterns", List.of()),
                    (List<String>) visaMap.getOrDefault("remote-eu-patterns", List.of()),
                    (List<String>) visaMap.getOrDefault("positive-patterns", List.of()),
                    (List<String>) visaMap.getOrDefault("negative-patterns", List.of()),
                    (String) visaMap.getOrDefault("unknown-action", "skip"),
                    aiFallback
            );
        }

        return new PersonalProfile.FilterConfig(role, location, yoe, language, visaSponsorship);
    }

    @SuppressWarnings("unchecked")
    private PersonalProfile.ScoringConfig parseScoring(Map<String, Object> scoringMap) {
        if (scoringMap == null) return null;

        double benchmarkWeight = scoringMap.containsKey("benchmark-weight")
                ? ((Number) scoringMap.get("benchmark-weight")).doubleValue() : 22.0;

        PersonalProfile.ScoringThresholds thresholds = null;
        Map<String, Object> thresholdsMap = (Map<String, Object>) scoringMap.get("thresholds");
        if (thresholdsMap != null) {
            thresholds = new PersonalProfile.ScoringThresholds(
                    thresholdsMap.containsKey("apply-score")
                            ? ((Number) thresholdsMap.get("apply-score")).intValue() : 40,
                    thresholdsMap.containsKey("apply-min-matches")
                            ? ((Number) thresholdsMap.get("apply-min-matches")).intValue() : 4,
                    thresholdsMap.containsKey("maybe-score")
                            ? ((Number) thresholdsMap.get("maybe-score")).intValue() : 25,
                    thresholdsMap.containsKey("maybe-min-matches")
                            ? ((Number) thresholdsMap.get("maybe-min-matches")).intValue() : 2
            );
        }

        List<String> bonusSignals = (List<String>) scoringMap.getOrDefault("bonus-signals", List.of());
        double bonusWeight = scoringMap.containsKey("bonus-weight")
                ? ((Number) scoringMap.get("bonus-weight")).doubleValue() : 2.0;

        Map<String, Double> skillWeights = new HashMap<>();
        Map<String, Object> weightsMap = (Map<String, Object>) scoringMap.get("skill-weights");
        if (weightsMap != null) {
            for (Map.Entry<String, Object> entry : weightsMap.entrySet()) {
                skillWeights.put(entry.getKey(), ((Number) entry.getValue()).doubleValue());
            }
        }

        Map<String, List<String>> skillVariants = new HashMap<>();
        Map<String, Object> variantsMap = (Map<String, Object>) scoringMap.get("skill-variants");
        if (variantsMap != null) {
            for (Map.Entry<String, Object> entry : variantsMap.entrySet()) {
                skillVariants.put(entry.getKey(), (List<String>) entry.getValue());
            }
        }

        List<String> primarySkills = (List<String>) scoringMap.getOrDefault("primary-skills", List.of());
        int primarySkillCap = scoringMap.containsKey("primary-skill-cap")
                ? ((Number) scoringMap.get("primary-skill-cap")).intValue() : 70;
        List<String> competingLanguages = (List<String>) scoringMap.getOrDefault("competing-languages", List.of());
        int competingLanguageCap = scoringMap.containsKey("competing-language-cap")
                ? ((Number) scoringMap.get("competing-language-cap")).intValue() : 50;

        PersonalProfile.SeniorityDiscountConfig seniorityDiscount = null;
        Map<String, Object> sdMap = (Map<String, Object>) scoringMap.get("seniority-discount");
        if (sdMap != null) {
            boolean enabled = (boolean) sdMap.getOrDefault("enabled", true);
            List<String> keywords = (List<String>) sdMap.getOrDefault("keywords", List.of());
            double multiplier = sdMap.containsKey("multiplier")
                    ? ((Number) sdMap.get("multiplier")).doubleValue() : 0.70;
            seniorityDiscount = new PersonalProfile.SeniorityDiscountConfig(enabled, keywords, multiplier);
        }

        return new PersonalProfile.ScoringConfig(
                benchmarkWeight, thresholds, bonusSignals, bonusWeight, skillWeights, skillVariants,
                primarySkills, primarySkillCap, competingLanguages, competingLanguageCap, seniorityDiscount);
    }

    @SuppressWarnings("unchecked")
    private PersonalProfile.LinkedInSearchConfig parseLinkedInSearch(Map<String, Object> searchMap) {
        if (searchMap == null) return null;
        String query = (String) searchMap.getOrDefault("query", "");
        List<String> locations = (List<String>) searchMap.getOrDefault("locations", List.of("Germany"));
        String datePosted = (String) searchMap.getOrDefault("date-posted", "week");
        return new PersonalProfile.LinkedInSearchConfig(query, locations, datePosted);
    }

    @SuppressWarnings("unchecked")
    private PersonalProfile.IndeedSearchConfig parseIndeedSearch(Map<String, Object> searchMap) {
        if (searchMap == null) return null;
        List<String> keywords = (List<String>) searchMap.getOrDefault("keywords", List.of());
        List<String> locations = (List<String>) searchMap.getOrDefault("locations", List.of("Germany"));
        int resultsWanted = searchMap.containsKey("results-wanted")
                ? ((Number) searchMap.get("results-wanted")).intValue() : 25;
        int hoursOld = searchMap.containsKey("hours-old")
                ? ((Number) searchMap.get("hours-old")).intValue() : 24;
        return new PersonalProfile.IndeedSearchConfig(keywords, locations, resultsWanted, hoursOld);
    }

    private PersonalProfile emptyProfile() {
        return new PersonalProfile("", "", 0, Collections.emptyList(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null, null, null);
    }
}
