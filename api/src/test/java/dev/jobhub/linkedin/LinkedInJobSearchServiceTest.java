package dev.jobhub.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jobhub.model.Company;
import dev.jobhub.model.JobPosting;
import dev.jobhub.model.enums.AtsType;
import dev.jobhub.model.enums.CompanyStatus;
import dev.jobhub.model.enums.DiscoverySource;
import dev.jobhub.repository.CompanyRepository;
import dev.jobhub.repository.JobPostingRepository;
import dev.jobhub.filter.DeduplicationFilter;
import dev.jobhub.filter.FilterResult;
import dev.jobhub.filter.LanguageFilter;
import dev.jobhub.filter.LocationFilter;
import dev.jobhub.filter.RoleRelevanceFilter;
import dev.jobhub.filter.YoeFilter;
import dev.jobhub.service.PersonalProfile;
import dev.jobhub.service.PersonalProfileLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LinkedInJobSearchServiceTest {

    private HttpMcpClient httpMcpClient;
    private LinkedInRateLimiter rateLimiter;
    private JobPostingRepository jobPostingRepository;
    private CompanyRepository companyRepository;
    private PersonalProfileLoader profileLoader;
    private LanguageFilter languageFilter;
    private RoleRelevanceFilter roleRelevanceFilter;
    private LocationFilter locationFilter;
    private YoeFilter yoeFilter;
    private DeduplicationFilter deduplicationFilter;
    private LinkedInJobSearchService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        httpMcpClient = mock(HttpMcpClient.class);
        rateLimiter = mock(LinkedInRateLimiter.class);
        jobPostingRepository = mock(JobPostingRepository.class);
        companyRepository = mock(CompanyRepository.class);
        profileLoader = mock(PersonalProfileLoader.class);
        languageFilter = mock(LanguageFilter.class);
        roleRelevanceFilter = mock(RoleRelevanceFilter.class);
        locationFilter = mock(LocationFilter.class);
        yoeFilter = mock(YoeFilter.class);
        deduplicationFilter = mock(DeduplicationFilter.class);
        service = new LinkedInJobSearchService(httpMcpClient, rateLimiter,
                jobPostingRepository, companyRepository, profileLoader, languageFilter,
                roleRelevanceFilter, locationFilter, yoeFilter, deduplicationFilter);
    }

    private PersonalProfile buildProfile(List<String> primarySkills, List<String> locations) {
        PersonalProfile.ScoringConfig scoring = new PersonalProfile.ScoringConfig(
                22.0, null, List.of(), 2.0, Map.of(), Map.of(),
                primarySkills, 70, List.of(), 50
        );
        PersonalProfile.Preferences prefs = new PersonalProfile.Preferences(
                locations, "FULL_TIME", 80000, List.of("senior"), List.of("en"), List.of()
        );
        return new PersonalProfile("Test", "Dev", 5,
                List.of(new PersonalProfile.ProfileSkill("Java", "expert", "LANGUAGE")),
                prefs, null, scoring, null, null);
    }

    private JsonNode buildSearchResponse(String searchText, List<String> jobIds) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode sc = root.putObject("structuredContent");
        ObjectNode sections = sc.putObject("sections");
        sections.put("search_results", searchText);
        ArrayNode ids = sc.putArray("job_ids");
        jobIds.forEach(ids::add);
        return root;
    }

    @Nested
    @DisplayName("searchAndMatch")
    class SearchAndMatchTests {

        @Test
        @DisplayName("Should search using profile primary skills and locations")
        void shouldUseProfileKeywordsAndLocations() {
            // Provide linkedInSearch with a query so extractSearchKeywords returns it
            PersonalProfile profile = new PersonalProfile("Test", "Dev", 5,
                    List.of(new PersonalProfile.ProfileSkill("Java", "expert", "LANGUAGE")),
                    new PersonalProfile.Preferences(List.of("Berlin"), "FULL_TIME", 80000, List.of("senior"), List.of("en"), List.of()),
                    null, new PersonalProfile.ScoringConfig(22, null, List.of(), 2, Map.of(), Map.of(),
                    List.of("java", "kotlin"), 70, List.of(), 50),
                    new PersonalProfile.LinkedInSearchConfig("java kotlin", List.of("Berlin"), null), null);
            when(profileLoader.getProfile()).thenReturn(profile);
            when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
            when(httpMcpClient.callTool(eq("search_jobs"), any())).thenReturn(buildSearchResponse("", List.of()));

            int[] result = service.searchAndMatch();

            assertThat(result[2]).isEqualTo(1); // 1 keyword x 1 location = 1 search
            verify(httpMcpClient, times(1)).callTool(eq("search_jobs"), any());
        }

        @Test
        @DisplayName("Should stop when rate limit hit")
        void shouldStopOnRateLimit() {
            PersonalProfile profile = buildProfile(List.of("java", "kotlin"), List.of("Berlin"));
            when(profileLoader.getProfile()).thenReturn(profile);
            when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true, false);
            when(httpMcpClient.callTool(eq("search_jobs"), any())).thenReturn(buildSearchResponse("", List.of()));

            int[] result = service.searchAndMatch();

            assertThat(result[2]).isEqualTo(1); // only 1 search completed
            verify(httpMcpClient, times(1)).callTool(eq("search_jobs"), any());
        }

        @Test
        @DisplayName("Should handle MCP client exceptions gracefully")
        void shouldHandleMcpException() {
            PersonalProfile profile = buildProfile(List.of("java"), List.of("Germany"));
            when(profileLoader.getProfile()).thenReturn(profile);
            when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
            when(httpMcpClient.callTool(eq("search_jobs"), any()))
                    .thenThrow(new RuntimeException("Connection failed"));

            int[] result = service.searchAndMatch();

            assertThat(result[0]).isZero(); // enriched
            assertThat(result[1]).isZero(); // created
            assertThat(result[2]).isEqualTo(1); // searched
        }
    }

    @Nested
    @DisplayName("processSearchResults")
    class ProcessSearchResultsTests {

        @BeforeEach
        void setUpFilters() {
            when(roleRelevanceFilter.filter(anyString())).thenReturn(FilterResult.keep());
            when(locationFilter.filter(anyString())).thenReturn(FilterResult.keep());
            when(yoeFilter.extractYoe(anyString())).thenReturn(null);
            when(yoeFilter.filter(any())).thenReturn(FilterResult.keep());
            when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString())).thenReturn("test-fp");
            when(jobPostingRepository.findAtsJobByFingerprint(anyString())).thenReturn(Optional.empty());
            when(jobPostingRepository.findFirstByFingerprintAndLanguageFilter(anyString(), any())).thenReturn(Optional.empty());
            when(jobPostingRepository.existsBySourceAndExternalId(any(), anyString())).thenReturn(false);
        }

        @Test
        @DisplayName("Should enrich existing job with LinkedIn link")
        void shouldEnrichExistingJob() {
            String searchText = "Backend Engineer (m/f/d)\nAcme Corp\nBerlin (Hybrid)\n";
            JsonNode response = buildSearchResponse(searchText, List.of("12345"));

            Company company = Company.builder().id(UUID.randomUUID()).name("Acme Corp")
                    .normalizedName("acme corp").build();
            JobPosting existingJob = JobPosting.builder()
                    .id(UUID.randomUUID())
                    .title("Backend Engineer")
                    .company(company)
                    .source(AtsType.GREENHOUSE)
                    .externalId("abc")
                    .build();

            when(jobPostingRepository.findByCompanyNormalizedNameAndTitleContaining(
                    eq("acme corp"), anyString())).thenReturn(List.of(existingJob));
            when(jobPostingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int[] result = service.processSearchResults(response);

            assertThat(result[0]).isEqualTo(1); // enriched
            assertThat(result[1]).isZero(); // created
            assertThat(existingJob.getExternalLinks()).containsEntry("linkedin",
                    "https://www.linkedin.com/jobs/view/12345/");
        }

        @Test
        @DisplayName("Should create new job when no match found")
        void shouldCreateNewJob() {
            String searchText = "Frontend Developer\nNewCo GmbH\nMunich (Remote)\n";
            JsonNode response = buildSearchResponse(searchText, List.of("67890"));

            when(jobPostingRepository.findByCompanyNormalizedNameAndTitleContaining(
                    anyString(), anyString())).thenReturn(List.of());

            Company newCompany = Company.builder()
                    .id(UUID.randomUUID()).name("NewCo GmbH").normalizedName("newco gmbh")
                    .status(CompanyStatus.DISCOVERED).build();
            when(companyRepository.findByNormalizedName("newco gmbh")).thenReturn(Optional.empty());
            when(companyRepository.save(any())).thenReturn(newCompany);
            when(jobPostingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int[] result = service.processSearchResults(response);

            assertThat(result[0]).isZero(); // enriched
            assertThat(result[1]).isEqualTo(1); // created

            ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
            verify(jobPostingRepository).save(captor.capture());
            JobPosting saved = captor.getValue();
            assertThat(saved.getSource()).isEqualTo(AtsType.LINKEDIN);
            assertThat(saved.getExternalId()).isEqualTo("67890");
            assertThat(saved.getTitle()).isEqualTo("Frontend Developer");
            assertThat(saved.getApplyUrl()).isEqualTo("https://www.linkedin.com/jobs/view/67890/");
            assertThat(saved.getExternalLinks()).containsEntry("linkedin",
                    "https://www.linkedin.com/jobs/view/67890/");
        }

        @Test
        @DisplayName("Should handle blank search text")
        void shouldHandleBlankSearchText() {
            JsonNode response = buildSearchResponse("", List.of("111"));

            int[] result = service.processSearchResults(response);

            assertThat(result[0]).isZero();
            assertThat(result[1]).isZero();
            verifyNoInteractions(jobPostingRepository);
        }

        @Test
        @DisplayName("Should handle empty job_ids array")
        void shouldHandleEmptyJobIds() {
            String searchText = "Engineer\nSomeCompany\nBerlin (Hybrid)\n";
            JsonNode response = buildSearchResponse(searchText, List.of());

            int[] result = service.processSearchResults(response);

            assertThat(result[0]).isZero();
            assertThat(result[1]).isZero();
        }

        @Test
        @DisplayName("Should reuse existing company when creating new job")
        void shouldReuseExistingCompany() {
            String searchText = "Dev\nKnownCo\nHamburg (On-site)\n";
            JsonNode response = buildSearchResponse(searchText, List.of("999"));

            Company existing = Company.builder().id(UUID.randomUUID())
                    .name("KnownCo").normalizedName("knownco").build();
            when(jobPostingRepository.findByCompanyNormalizedNameAndTitleContaining(
                    anyString(), anyString())).thenReturn(List.of());
            when(companyRepository.findByNormalizedName("knownco")).thenReturn(Optional.of(existing));
            when(jobPostingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.processSearchResults(response);

            verify(companyRepository, never()).save(any());
            ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
            verify(jobPostingRepository).save(captor.capture());
            assertThat(captor.getValue().getCompany()).isEqualTo(existing);
        }

        @Test
        @DisplayName("Should process multiple jobs from single response")
        void shouldProcessMultipleJobs() {
            String searchText = "Engineer A\nCompany1\nBerlin (Remote)\n\n"
                    + "Engineer B\nCompany2\nMunich (Hybrid)\n";
            JsonNode response = buildSearchResponse(searchText, List.of("aaa", "bbb"));

            when(jobPostingRepository.findByCompanyNormalizedNameAndTitleContaining(
                    anyString(), anyString())).thenReturn(List.of());
            when(companyRepository.findByNormalizedName(anyString())).thenReturn(Optional.empty());
            Company c = Company.builder().id(UUID.randomUUID()).name("X").normalizedName("x").build();
            when(companyRepository.save(any())).thenReturn(c);
            when(jobPostingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            int[] result = service.processSearchResults(response);

            assertThat(result[1]).isEqualTo(2); // 2 created
        }
    }

    @Nested
    @DisplayName("parseJobs")
    class ParseJobsTests {

        @Test
        @DisplayName("Should parse standard format: title, company, location")
        void shouldParseStandardFormat() {
            String[] lines = {"Backend Engineer", "TechCo", "Berlin (Hybrid)"};
            var result = service.parseJobs(lines);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).title()).isEqualTo("Backend Engineer");
            assertThat(result.get(0).company()).isEqualTo("TechCo");
            assertThat(result.get(0).location()).isEqualTo("Berlin (Hybrid)");
        }

        @Test
        @DisplayName("Should skip 'with verification' lines")
        void shouldSkipVerificationLines() {
            String[] lines = {"Senior Developer", "with verification", "CompanyX", "Munich (Remote)"};
            var result = service.parseJobs(lines);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).title()).isEqualTo("Senior Developer");
            assertThat(result.get(0).company()).isEqualTo("CompanyX");
        }

        @Test
        @DisplayName("Should filter out noise lines (results, Set alert)")
        void shouldFilterNoise() {
            String[] lines = {"Some Title", "25 results", "Berlin (Hybrid)"};
            var result = service.parseJobs(lines);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should handle On-Site and Onsite variants")
        void shouldHandleLocationVariants() {
            String[] lines1 = {"Dev", "CoA", "Frankfurt (On-Site)"};
            String[] lines2 = {"Dev", "CoB", "Hamburg (Onsite)"};
            assertThat(service.parseJobs(lines1)).hasSize(1);
            assertThat(service.parseJobs(lines2)).hasSize(1);
        }

        @Test
        @DisplayName("Should return empty for text with no location patterns")
        void shouldReturnEmptyWithNoLocations() {
            String[] lines = {"Just some random", "text without", "any location patterns"};
            assertThat(service.parseJobs(lines)).isEmpty();
        }
    }

    @Nested
    @DisplayName("findBestTitleMatch")
    class FindBestTitleMatchTests {

        @Test
        @DisplayName("Should match when candidate contains target")
        void shouldMatchContains() {
            JobPosting jp = JobPosting.builder().title("Senior Backend Engineer").build();
            var result = service.findBestTitleMatch(List.of(jp), "Backend Engineer");
            assertThat(result).isEqualTo(jp);
        }

        @Test
        @DisplayName("Should match when target contains candidate")
        void shouldMatchReverseContains() {
            JobPosting jp = JobPosting.builder().title("Engineer").build();
            var result = service.findBestTitleMatch(List.of(jp), "Backend Engineer (m/f/d)");
            assertThat(result).isEqualTo(jp);
        }

        @Test
        @DisplayName("Should fallback to first candidate")
        void shouldFallbackToFirst() {
            JobPosting jp1 = JobPosting.builder().title("Frontend Dev").build();
            JobPosting jp2 = JobPosting.builder().title("QA Engineer").build();
            var result = service.findBestTitleMatch(List.of(jp1, jp2), "Backend Architect");
            assertThat(result).isEqualTo(jp1);
        }

        @Test
        @DisplayName("Should return null for empty list")
        void shouldReturnNullForEmpty() {
            assertThat(service.findBestTitleMatch(List.of(), "Dev")).isNull();
        }
    }

    @Nested
    @DisplayName("extractTitleKeyword")
    class ExtractTitleKeywordTests {

        @Test
        @DisplayName("Should extract meaningful keyword, skipping stopwords")
        void shouldExtractMeaningfulKeyword() {
            assertThat(service.extractTitleKeyword("Senior Backend Engineer (m/f/d)"))
                    .isEqualTo("Backend");
        }

        @Test
        @DisplayName("Should skip level words like senior, junior, staff")
        void shouldSkipLevelWords() {
            assertThat(service.extractTitleKeyword("Staff Engineer"))
                    .isEqualTo("Engineer");
        }

        @Test
        @DisplayName("Should handle short title")
        void shouldHandleShortTitle() {
            assertThat(service.extractTitleKeyword("Dev")).isEqualTo("Dev");
        }

        @Test
        @DisplayName("Should strip parenthesized content")
        void shouldStripParens() {
            String result = service.extractTitleKeyword("Developer (all genders)");
            assertThat(result).isEqualTo("Developer");
        }
    }

    @Nested
    @DisplayName("extractSearchKeywords")
    class ExtractSearchKeywordsTests {

        @Test
        @DisplayName("Should use primary skills from scoring config")
        void shouldUsePrimarySkills() {
            PersonalProfile profile = new PersonalProfile("Test", "Dev", 5,
                    List.of(new PersonalProfile.ProfileSkill("Java", "expert", "LANGUAGE")),
                    new PersonalProfile.Preferences(List.of("Berlin"), "FULL_TIME", 80000, List.of("senior"), List.of("en"), List.of()),
                    null, new PersonalProfile.ScoringConfig(22, null, List.of(), 2, Map.of(), Map.of(),
                    List.of("java", "spring boot"), 70, List.of(), 50),
                    new PersonalProfile.LinkedInSearchConfig("java spring boot", List.of("Berlin"), null), null);
            assertThat(service.extractSearchKeywords(profile)).containsExactly("java spring boot");
        }

        @Test
        @DisplayName("Should fallback to expert skills when no primary skills")
        void shouldFallbackToExpertSkills() {
            PersonalProfile profile = new PersonalProfile("Test", "Dev", 5,
                    List.of(
                            new PersonalProfile.ProfileSkill("Java", "expert", "LANGUAGE"),
                            new PersonalProfile.ProfileSkill("Python", "intermediate", "LANGUAGE"),
                            new PersonalProfile.ProfileSkill("Kotlin", "advanced", "LANGUAGE")
                    ),
                    new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                    null, new PersonalProfile.ScoringConfig(22, null, List.of(), 2, Map.of(), Map.of(),
                    List.of(), 70, List.of(), 50), null, null);
            // No linkedInSearch config and no primary skills → returns hardcoded fallback boolean query
            assertThat(service.extractSearchKeywords(profile)).hasSize(1);
            assertThat(service.extractSearchKeywords(profile).get(0)).contains("software OR java OR backend");
        }
    }

    @Nested
    @DisplayName("extractLocations")
    class ExtractLocationsTests {

        @Test
        @DisplayName("Should use profile preference locations")
        void shouldUsePreferenceLocations() {
            PersonalProfile profile = buildProfile(List.of("java"), List.of("Berlin", "Munich", "Hamburg"));
            assertThat(service.extractLocations(profile)).containsExactly("Berlin", "Munich", "Hamburg");
        }

        @Test
        @DisplayName("Should limit to 3 locations")
        void shouldLimitTo3() {
            PersonalProfile profile = buildProfile(List.of("java"),
                    List.of("Berlin", "Munich", "Hamburg", "Frankfurt", "Cologne"));
            assertThat(service.extractLocations(profile)).hasSize(3);
        }

        @Test
        @DisplayName("Should default to Germany when no locations configured")
        void shouldDefaultToGermany() {
            PersonalProfile profile = buildProfile(List.of("java"), List.of());
            assertThat(service.extractLocations(profile)).containsExactly("Germany");
        }
    }
}
