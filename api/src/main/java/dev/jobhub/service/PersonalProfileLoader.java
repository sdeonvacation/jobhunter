package dev.jobhub.service;

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

        return new PersonalProfile(name, title, years, skills, preferences, filters, scoring);
    }

    @SuppressWarnings("unchecked")
    private PersonalProfile.FilterConfig parseFilters(Map<String, Object> filtersMap) {
        if (filtersMap == null) return null;

        PersonalProfile.RoleFilterConfig role = null;
        PersonalProfile.LocationFilterConfig location = null;
        PersonalProfile.YoeFilterConfig yoe = null;

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
                    (List<String>) locationMap.getOrDefault("germany-cities", List.of()),
                    (List<String>) locationMap.getOrDefault("remote-patterns", List.of())
            );
        }

        Map<String, Object> yoeMap = (Map<String, Object>) filtersMap.get("yoe");
        if (yoeMap != null) {
            int maxYears = yoeMap.containsKey("max-years")
                    ? ((Number) yoeMap.get("max-years")).intValue() : 5;
            yoe = new PersonalProfile.YoeFilterConfig(maxYears);
        }

        return new PersonalProfile.FilterConfig(role, location, yoe);
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

        return new PersonalProfile.ScoringConfig(
                benchmarkWeight, thresholds, bonusSignals, bonusWeight, skillWeights, skillVariants);
    }

    private PersonalProfile emptyProfile() {
        return new PersonalProfile("", "", 0, Collections.emptyList(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null);
    }
}
