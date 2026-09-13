package dev.jobhunter.service;

import lombok.extern.slf4j.Slf4j;
import dev.jobhunter.filter.FilterOverrides;
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
import java.util.LinkedHashMap;
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

    /** Named, reusable filter profiles parsed from profile.yaml {@code filter-profiles}. */
    private Map<String, FilterOverrides> filterProfiles = Map.of();

    /** Fully-resolved per-source overrides keyed by source config {@code name}. */
    private Map<String, FilterOverrides> sourceFilterOverrides = Map.of();

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void load() {
        try (InputStream is = profileResource.getInputStream()) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(is);
            this.profile = parseProfile(data);
            this.filterProfiles = parseFilterProfiles(
                    (Map<String, Object>) data.getOrDefault("filter-profiles", null));
            this.sourceFilterOverrides = resolveSourceFilterOverrides(
                    (Map<String, Object>) data.getOrDefault("source-filter-overrides", null),
                    this.filterProfiles);
            log.info("Personal profile loaded: {} with {} skills",
                    profile.name(), profile.skills().size());
        } catch (IOException e) {
            log.warn("Could not load profile.yaml, using empty profile: {}", e.getMessage());
            this.profile = emptyProfile();
            this.filterProfiles = Map.of();
            this.sourceFilterOverrides = Map.of();
        }
    }

    public PersonalProfile getProfile() {
        return profile;
    }

    /** Named reusable profiles (introspection/tests). */
    public Map<String, FilterOverrides> getFilterProfiles() {
        return filterProfiles;
    }

    /** Resolved per-source overrides; a source absent from the map behaves as {@link FilterOverrides#NONE}. */
    public Map<String, FilterOverrides> getSourceFilterOverrides() {
        return sourceFilterOverrides;
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
            location = new PersonalProfile.LocationFilterConfig(
                    (List<String>) locationMap.getOrDefault("remote-patterns", List.of()),
                    (String) locationMap.getOrDefault("unknown-action", "skip")
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
            List<String> detectLanguages = (List<String>) languageMap.getOrDefault("detect-languages", List.of());
            double confidenceThreshold = languageMap.containsKey("confidence-threshold")
                    ? ((Number) languageMap.get("confidence-threshold")).doubleValue() : 0.85;
            List<String> excludePatterns = (List<String>) languageMap.getOrDefault("exclude-patterns", List.of());
            List<String> softQualifierPatterns = (List<String>) languageMap.getOrDefault("soft-qualifier-patterns", List.of());
            language = new PersonalProfile.LanguageFilterConfig(target, detectLanguages, confidenceThreshold, excludePatterns, softQualifierPatterns);
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

    @SuppressWarnings("unchecked")
    private Map<String, FilterOverrides> parseFilterProfiles(Map<String, Object> profilesMap) {
        if (profilesMap == null) return Map.of();

        Map<String, FilterOverrides> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : profilesMap.entrySet()) {
            result.put(entry.getKey(), parseOverrides((Map<String, Object>) entry.getValue()));
        }
        return result;
    }

    /** Build a FilterOverrides from a raw map (named profile or inline top-level override). */
    @SuppressWarnings("unchecked")
    private FilterOverrides parseOverrides(Map<String, Object> overridesMap) {
        if (overridesMap == null) return FilterOverrides.NONE;

        boolean languageExempt = Boolean.TRUE.equals(overridesMap.get("language-exempt"));

        List<String> includePatterns = List.of();
        List<String> excludeKeywords = List.of();
        Map<String, Object> roleMap = (Map<String, Object>) overridesMap.get("role");
        if (roleMap != null) {
            includePatterns = (List<String>) roleMap.getOrDefault("include-patterns", List.of());
            excludeKeywords = (List<String>) roleMap.getOrDefault("exclude-keywords", List.of());
        }

        return new FilterOverrides(nullSafe(includePatterns), nullSafe(excludeKeywords), languageExempt);
    }

    /**
     * Resolve {@code source-filter-overrides} entries to a per-source map. A entry either
     * references a named profile ({@code profile:}) — with optional inline keys merged over it —
     * or is defined fully inline. A missing referenced profile warns and resolves to NONE.
     */
    @SuppressWarnings("unchecked")
    private Map<String, FilterOverrides> resolveSourceFilterOverrides(Map<String, Object> overridesMap,
                                                                      Map<String, FilterOverrides> profiles) {
        if (overridesMap == null) return Map.of();

        Map<String, FilterOverrides> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : overridesMap.entrySet()) {
            String sourceName = entry.getKey();
            Map<String, Object> sourceMap = (Map<String, Object>) entry.getValue();
            if (sourceMap == null) {
                result.put(sourceName, FilterOverrides.NONE);
                continue;
            }

            FilterOverrides base = FilterOverrides.NONE;
            Object profileRef = sourceMap.get("profile");
            if (profileRef instanceof String name) {
                FilterOverrides referenced = profiles.get(name);
                if (referenced == null) {
                    log.warn("source-filter-overrides.{} references unknown filter profile '{}'; using NONE",
                            sourceName, name);
                } else {
                    base = referenced;
                }
            }

            result.put(sourceName, mergeOverrides(base, sourceMap));
        }
        return result;
    }

    /** Merge inline {@code language-exempt}/role keys over a base override (inline wins when present). */
    @SuppressWarnings("unchecked")
    private FilterOverrides mergeOverrides(FilterOverrides base, Map<String, Object> sourceMap) {
        boolean languageExempt = sourceMap.containsKey("language-exempt")
                ? Boolean.TRUE.equals(sourceMap.get("language-exempt"))
                : base.languageExempt();

        List<String> includePatterns = nullSafe(base.roleIncludePatterns());
        List<String> excludeKeywords = nullSafe(base.roleExcludeKeywords());

        Object roleValue = sourceMap.get("role");
        if (roleValue instanceof Map<?, ?> roleMap) {
            if (roleMap.containsKey("include-patterns")) {
                includePatterns = nullSafe((List<String>) roleMap.get("include-patterns"));
            }
            if (roleMap.containsKey("exclude-keywords")) {
                excludeKeywords = nullSafe((List<String>) roleMap.get("exclude-keywords"));
            }
        }

        return new FilterOverrides(includePatterns, excludeKeywords, languageExempt);
    }

    private static List<String> nullSafe(List<String> value) {
        return value != null ? value : List.of();
    }

    private PersonalProfile emptyProfile() {
        return new PersonalProfile("", "", 0, Collections.emptyList(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null, null, null);
    }
}
