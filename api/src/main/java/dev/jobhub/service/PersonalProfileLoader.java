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

        return new PersonalProfile(name, title, years, skills, preferences);
    }

    private PersonalProfile emptyProfile() {
        return new PersonalProfile("", "", 0, Collections.emptyList(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()));
    }
}
