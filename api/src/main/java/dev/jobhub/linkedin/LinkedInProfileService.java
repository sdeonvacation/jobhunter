package dev.jobhub.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhub.repository.ProfileCacheRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class LinkedInProfileService {

    private static final int CACHE_DAYS = 7;

    private final HttpMcpClient httpMcpClient;
    private final LinkedInRateLimiter rateLimiter;
    private final ProfileCacheRepository cacheRepository;
    private final ObjectMapper objectMapper;

    public LinkedInProfileService(HttpMcpClient httpMcpClient,
                                  LinkedInRateLimiter rateLimiter,
                                  ProfileCacheRepository cacheRepository,
                                  ObjectMapper objectMapper) {
        this.httpMcpClient = httpMcpClient;
        this.rateLimiter = rateLimiter;
        this.cacheRepository = cacheRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Get a person's LinkedIn profile with caching.
     */
    public ProfileData getProfile(String linkedinUrl) {
        // Check cache first
        Optional<ProfileCache> cached = cacheRepository
                .findByLinkedinUrlAndExpiresAtAfter(linkedinUrl, LocalDateTime.now());

        if (cached.isPresent()) {
            log.debug("Cache hit for profile: {}", linkedinUrl);
            return deserializeProfileData(cached.get().getProfileData());
        }

        // Cache miss - fetch from LinkedIn
        if (!rateLimiter.acquire(ToolCategory.PROFILE)) {
            log.warn("Rate limit reached for PROFILE, cannot fetch: {}", linkedinUrl);
            return null;
        }

        Map<String, Object> params = Map.of(
                "linkedin_url", linkedinUrl,
                "sections", List.of("experience", "skills", "posts")
        );

        try {
            JsonNode response = httpMcpClient.callTool("get_person_profile", params);
            ProfileData profileData = parseProfileResponse(response);

            // Store in cache
            Map<String, Object> serializedData = serializeProfileData(profileData);
            ProfileCache cacheEntry = ProfileCache.builder()
                    .linkedinUrl(linkedinUrl)
                    .profileData(serializedData)
                    .fetchedAt(LocalDateTime.now())
                    .expiresAt(LocalDateTime.now().plusDays(CACHE_DAYS))
                    .build();
            cacheRepository.save(cacheEntry);

            log.info("Fetched and cached profile for: {}", linkedinUrl);
            return profileData;
        } catch (Exception e) {
            log.error("Failed to fetch profile for {}: {}", linkedinUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Get sidebar/related profiles from a LinkedIn profile page.
     */
    public List<ProfileSummary> getSidebarProfiles(String linkedinUrl) {
        if (!rateLimiter.acquire(ToolCategory.PROFILE)) {
            log.warn("Rate limit reached for PROFILE, cannot fetch sidebar: {}", linkedinUrl);
            return List.of();
        }

        Map<String, Object> params = Map.of("linkedin_url", linkedinUrl);
        try {
            JsonNode response = httpMcpClient.callTool("get_sidebar_profiles", params);
            return parseSidebarResponse(response);
        } catch (Exception e) {
            log.error("Failed to fetch sidebar profiles for {}: {}", linkedinUrl, e.getMessage());
            return List.of();
        }
    }

    /**
     * Invalidate cached profile data.
     */
    public void invalidateCache(String linkedinUrl) {
        cacheRepository.deleteByLinkedinUrl(linkedinUrl);
        log.debug("Cache invalidated for: {}", linkedinUrl);
    }

    private ProfileData parseProfileResponse(JsonNode response) {
        String name = getTextOrNull(response, "name", "full_name");
        String headline = getTextOrNull(response, "headline", "title");
        String company = getTextOrNull(response, "company", "current_company");

        List<Experience> experience = parseExperience(response.path("experience"));
        List<String> skills = parseSkills(response.path("skills"));
        List<PostSummary> recentPosts = parsePosts(response.path("posts"));

        return new ProfileData(name, headline, company, experience, skills, recentPosts);
    }

    private List<Experience> parseExperience(JsonNode experienceNode) {
        List<Experience> experiences = new ArrayList<>();
        if (!experienceNode.isArray()) {
            return experiences;
        }

        for (JsonNode exp : experienceNode) {
            String title = getTextOrNull(exp, "title", "role");
            String company = getTextOrNull(exp, "company", "company_name");
            String duration = getTextOrNull(exp, "duration", "dates", "time_period");
            if (title != null) {
                experiences.add(new Experience(title, company, duration));
            }
        }

        return experiences;
    }

    private List<String> parseSkills(JsonNode skillsNode) {
        List<String> skills = new ArrayList<>();
        if (!skillsNode.isArray()) {
            return skills;
        }

        for (JsonNode skill : skillsNode) {
            if (skill.isTextual()) {
                skills.add(skill.asText());
            } else {
                String name = getTextOrNull(skill, "name", "skill");
                if (name != null) {
                    skills.add(name);
                }
            }
        }

        return skills;
    }

    private List<PostSummary> parsePosts(JsonNode postsNode) {
        List<PostSummary> posts = new ArrayList<>();
        if (!postsNode.isArray()) {
            return posts;
        }

        for (JsonNode post : postsNode) {
            String text = getTextOrNull(post, "text", "content", "summary");
            String date = getTextOrNull(post, "date", "posted_at", "created_at");
            int reactions = post.path("reactions").asInt(0);
            if (text != null) {
                posts.add(new PostSummary(text, date, reactions));
            }
        }

        return posts;
    }

    private List<ProfileSummary> parseSidebarResponse(JsonNode response) {
        List<ProfileSummary> profiles = new ArrayList<>();

        JsonNode people = response.path("profiles");
        if (!people.isArray()) {
            people = response.isArray() ? response : response.path("results");
        }

        if (!people.isArray()) {
            return profiles;
        }

        for (JsonNode person : people) {
            String name = getTextOrNull(person, "name", "full_name");
            String title = getTextOrNull(person, "title", "headline");
            String url = getTextOrNull(person, "linkedin_url", "url", "profile_url");
            if (name != null && url != null) {
                profiles.add(new ProfileSummary(name, title, url));
            }
        }

        return profiles;
    }

    @SuppressWarnings("unchecked")
    private ProfileData deserializeProfileData(Map<String, Object> data) {
        if (data == null) {
            return null;
        }

        String name = (String) data.get("name");
        String headline = (String) data.get("headline");
        String company = (String) data.get("company");

        List<Experience> experience = new ArrayList<>();
        List<Map<String, String>> expList = (List<Map<String, String>>) data.get("experience");
        if (expList != null) {
            for (Map<String, String> exp : expList) {
                experience.add(new Experience(exp.get("title"), exp.get("company"), exp.get("duration")));
            }
        }

        List<String> skills = (List<String>) data.getOrDefault("skills", List.of());

        List<PostSummary> posts = new ArrayList<>();
        List<Map<String, Object>> postList = (List<Map<String, Object>>) data.get("recentPosts");
        if (postList != null) {
            for (Map<String, Object> post : postList) {
                posts.add(new PostSummary(
                        (String) post.get("text"),
                        (String) post.get("date"),
                        post.get("reactions") != null ? ((Number) post.get("reactions")).intValue() : 0
                ));
            }
        }

        return new ProfileData(name, headline, company, experience, skills, posts);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> serializeProfileData(ProfileData profileData) {
        if (profileData == null) {
            return Map.of();
        }
        return objectMapper.convertValue(profileData, Map.class);
    }

    private String getTextOrNull(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    // Data records

    public record ProfileData(
            String name,
            String headline,
            String company,
            List<Experience> experience,
            List<String> skills,
            List<PostSummary> recentPosts
    ) {}

    public record Experience(String title, String company, String duration) {}

    public record PostSummary(String text, String date, int reactions) {}

    public record ProfileSummary(String name, String title, String linkedinUrl) {}
}
