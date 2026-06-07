package dev.jobhub.indeed;

import dev.jobhub.filter.DeduplicationFilter;
import dev.jobhub.filter.FilterResult;
import dev.jobhub.filter.LanguageFilter;
import dev.jobhub.filter.LocationFilter;
import dev.jobhub.filter.RoleRelevanceFilter;
import dev.jobhub.filter.YoeFilter;
import dev.jobhub.indeed.IndeedJobSearchService.IndeedJob;
import dev.jobhub.model.Company;
import dev.jobhub.model.JobPosting;
import dev.jobhub.model.enums.AtsType;
import dev.jobhub.model.enums.FilterDecision;
import dev.jobhub.repository.CompanyRepository;
import dev.jobhub.repository.JobPostingRepository;
import dev.jobhub.service.PersonalProfile;
import dev.jobhub.service.PersonalProfileLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IndeedJobSearchServiceTest {

    private JobPostingRepository jobPostingRepository;
    private CompanyRepository companyRepository;
    private PersonalProfileLoader profileLoader;
    private LanguageFilter languageFilter;
    private RoleRelevanceFilter roleRelevanceFilter;
    private LocationFilter locationFilter;
    private YoeFilter yoeFilter;
    private DeduplicationFilter deduplicationFilter;
    private IndeedJobSearchService service;

    @BeforeEach
    void setUp() {
        jobPostingRepository = mock(JobPostingRepository.class);
        companyRepository = mock(CompanyRepository.class);
        profileLoader = mock(PersonalProfileLoader.class);
        languageFilter = mock(LanguageFilter.class);
        roleRelevanceFilter = mock(RoleRelevanceFilter.class);
        locationFilter = mock(LocationFilter.class);
        yoeFilter = mock(YoeFilter.class);
        deduplicationFilter = mock(DeduplicationFilter.class);
        service = new IndeedJobSearchService(jobPostingRepository,
                companyRepository, profileLoader, languageFilter,
                roleRelevanceFilter, locationFilter, yoeFilter, deduplicationFilter);
    }

    private PersonalProfile buildProfile(PersonalProfile.IndeedSearchConfig indeedConfig) {
        PersonalProfile.Preferences prefs = new PersonalProfile.Preferences(
                List.of("Germany"), "FULL_TIME", 80000, List.of("senior"), List.of("en"), List.of()
        );
        return new PersonalProfile("Test", "Dev", 5,
                List.of(new PersonalProfile.ProfileSkill("Java", "expert", "LANGUAGE")),
                prefs, null, null, null, indeedConfig);
    }

    @Nested
    @DisplayName("searchAndCreate")
    class SearchAndCreateTests {

        @Test
        @DisplayName("Should return zeros when empty keywords")
        void shouldReturnZerosWhenEmptyKeywords() {
            PersonalProfile.IndeedSearchConfig config = new PersonalProfile.IndeedSearchConfig(
                    List.of(), List.of("Germany"), 25, 24
            );
            when(profileLoader.getProfile()).thenReturn(buildProfile(config));

            int[] stats = service.searchAndCreate();

            assertThat(stats).containsExactly(0, 0, 0);
        }

        @Test
        @DisplayName("Should return zeros when empty locations")
        void shouldReturnZerosWhenEmptyLocations() {
            PersonalProfile.IndeedSearchConfig config = new PersonalProfile.IndeedSearchConfig(
                    List.of("Java developer"), List.of(), 25, 24
            );
            when(profileLoader.getProfile()).thenReturn(buildProfile(config));

            int[] stats = service.searchAndCreate();

            assertThat(stats).containsExactly(0, 0, 0);
        }

        @Test
        @DisplayName("Should use fallback keywords when no IndeedSearchConfig")
        void shouldUseFallbackWhenNoConfig() {
            when(profileLoader.getProfile()).thenReturn(buildProfile(null));

            // Will fail on scrapeIndeed (no npx in test), but verifies config is parsed
            int[] stats = service.searchAndCreate();

            // Both fallback keywords tried with preferences location "Germany"
            assertThat(stats[2]).isEqualTo(2); // 2 searches attempted
        }
    }

    @Nested
    @DisplayName("processJobs")
    class ProcessJobsTests {

        @BeforeEach
        void setUpFilters() {
            when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
            when(roleRelevanceFilter.filter(anyString())).thenReturn(FilterResult.keep());
            when(locationFilter.filter(anyString())).thenReturn(FilterResult.keep());
            when(yoeFilter.extractYoe(anyString())).thenReturn(null);
            when(yoeFilter.filter(any())).thenReturn(FilterResult.keep());
            when(deduplicationFilter.generateFingerprint(anyString(), anyString(), anyString())).thenReturn("test-fp");
            when(companyRepository.findByNormalizedName(anyString())).thenReturn(Optional.empty());
            when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));
            when(jobPostingRepository.existsBySourceAndExternalId(any(), anyString())).thenReturn(false);
            when(jobPostingRepository.findFirstByFingerprintAndLanguageFilter(anyString(), any())).thenReturn(Optional.empty());
            when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("Should create job postings from IndeedJob list")
        void shouldCreateJobPostings() {
            List<IndeedJob> jobs = List.of(
                    new IndeedJob("in-abc123", "indeed", "https://de.indeed.com/viewjob?jk=abc123",
                            null, "Backend Engineer", "SAP", "Berlin", "2026-06-07", false, "Java Spring Boot"),
                    new IndeedJob("in-def456", "indeed", "https://de.indeed.com/viewjob?jk=def456",
                            null, "Java Dev", "Siemens", "Munich", "2026-06-07", false, "Kotlin developer")
            );

            int[] stats = service.processJobs(jobs);

            assertThat(stats[0]).isEqualTo(2); // created
            assertThat(stats[1]).isEqualTo(0); // filtered

            ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
            verify(jobPostingRepository, times(2)).save(captor.capture());

            JobPosting first = captor.getAllValues().get(0);
            assertThat(first.getSource()).isEqualTo(AtsType.INDEED);
            assertThat(first.getExternalId()).isEqualTo("in-abc123");
            assertThat(first.getTitle()).isEqualTo("Backend Engineer");
            assertThat(first.getLocation()).isEqualTo("Berlin");
            assertThat(first.getDescription()).isEqualTo("Java Spring Boot");
            assertThat(first.getApplyUrl()).isEqualTo("https://de.indeed.com/viewjob?jk=abc123");
            assertThat(first.getExternalLinks()).containsEntry("indeed", "https://de.indeed.com/viewjob?jk=abc123");

            JobPosting second = captor.getAllValues().get(1);
            assertThat(second.getExternalId()).isEqualTo("in-def456");
        }

        @Test
        @DisplayName("Should skip jobs with blank title or company")
        void shouldSkipBlankTitleOrCompany() {
            List<IndeedJob> jobs = List.of(
                    new IndeedJob("in-1", "indeed", "https://url", null, "", "SAP", "Berlin", null, false, "desc"),
                    new IndeedJob("in-2", "indeed", "https://url", null, "Title", "", "Berlin", null, false, "desc")
            );

            int[] stats = service.processJobs(jobs);

            assertThat(stats[0]).isEqualTo(0);
            verify(jobPostingRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should skip duplicates by externalId")
        void shouldSkipDuplicates() {
            when(jobPostingRepository.existsBySourceAndExternalId(AtsType.INDEED, "in-abc123"))
                    .thenReturn(true);

            List<IndeedJob> jobs = List.of(
                    new IndeedJob("in-abc123", "indeed", "https://de.indeed.com/viewjob?jk=abc123",
                            null, "Engineer", "SAP", "Berlin", null, false, "desc")
            );

            int[] stats = service.processJobs(jobs);

            assertThat(stats[0]).isEqualTo(0);
            verify(jobPostingRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should apply language filter and persist filtered jobs")
        void shouldApplyLanguageFilter() {
            when(languageFilter.filter(anyString(), anyString()))
                    .thenReturn(FilterResult.skip("German JD"));

            List<IndeedJob> jobs = List.of(
                    new IndeedJob("in-abc", "indeed", "https://de.indeed.com/viewjob?jk=abc",
                            null, "Entwickler", "SAP", "Berlin", null, false, "Wir suchen einen Entwickler")
            );

            int[] stats = service.processJobs(jobs);

            assertThat(stats[0]).isEqualTo(1); // still created
            assertThat(stats[1]).isEqualTo(1); // counted as filtered

            ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
            verify(jobPostingRepository).save(captor.capture());
            assertThat(captor.getValue().getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
            assertThat(captor.getValue().getFilterReason()).isEqualTo("German JD");
        }

        @Test
        @DisplayName("Should apply YOE filter when language passes")
        void shouldApplyYoeFilter() {
            when(languageFilter.filter(anyString(), anyString())).thenReturn(FilterResult.keep());
            when(yoeFilter.extractYoe(anyString())).thenReturn(10);
            when(yoeFilter.filter(10)).thenReturn(FilterResult.skip("requires 10+ years experience"));

            List<IndeedJob> jobs = List.of(
                    new IndeedJob("in-abc", "indeed", "https://url", null,
                            "Senior", "SAP", "Berlin", null, false, "10+ years experience required")
            );

            int[] stats = service.processJobs(jobs);

            assertThat(stats[1]).isEqualTo(1);
            ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
            verify(jobPostingRepository).save(captor.capture());
            assertThat(captor.getValue().getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
            assertThat(captor.getValue().getFilterReason()).isEqualTo("requires 10+ years experience");
            assertThat(captor.getValue().getRequiredYoe()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should skip YOE filter when language fails")
        void shouldSkipYoeWhenLanguageFails() {
            when(languageFilter.filter(anyString(), anyString()))
                    .thenReturn(FilterResult.skip("German JD"));

            List<IndeedJob> jobs = List.of(
                    new IndeedJob("in-abc", "indeed", "https://url", null,
                            "Dev", "SAP", "Berlin", null, false, "some description")
            );

            service.processJobs(jobs);

            verify(yoeFilter, never()).extractYoe(anyString());
        }

        @Test
        @DisplayName("Should reuse existing company")
        void shouldReuseExistingCompany() {
            Company existing = Company.builder().name("SAP").normalizedName("sap").build();
            when(companyRepository.findByNormalizedName("sap")).thenReturn(Optional.of(existing));

            List<IndeedJob> jobs = List.of(
                    new IndeedJob("in-abc", "indeed", "https://url", null,
                            "Dev", "SAP", "Berlin", null, false, "desc")
            );

            service.processJobs(jobs);

            verify(companyRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should create new company when not found")
        void shouldCreateNewCompany() {
            when(companyRepository.findByNormalizedName("new corp")).thenReturn(Optional.empty());

            List<IndeedJob> jobs = List.of(
                    new IndeedJob("in-abc", "indeed", "https://url", null,
                            "Dev", "New Corp", "Berlin", null, false, "desc")
            );

            service.processJobs(jobs);

            verify(companyRepository).save(argThat(c ->
                    "New Corp".equals(c.getName()) && "new corp".equals(c.getNormalizedName())));
        }

        @Test
        @DisplayName("Should handle null/empty job list")
        void shouldHandleEmptyList() {
            assertThat(service.processJobs(null)).containsExactly(0, 0);
            assertThat(service.processJobs(List.of())).containsExactly(0, 0);
        }

        @Test
        @DisplayName("Should handle job without description - no filter applied")
        void shouldHandleJobWithoutDescription() {
            List<IndeedJob> jobs = List.of(
                    new IndeedJob("in-abc", "indeed", "https://url", null,
                            "Dev", "SAP", "Berlin", null, false, null)
            );

            int[] stats = service.processJobs(jobs);

            assertThat(stats[0]).isEqualTo(1);
            verify(languageFilter, never()).filter(anyString(), anyString());

            ArgumentCaptor<JobPosting> captor = ArgumentCaptor.forClass(JobPosting.class);
            verify(jobPostingRepository).save(captor.capture());
            assertThat(captor.getValue().getDescription()).isNull();
            assertThat(captor.getValue().getLanguageFilter()).isEqualTo(FilterDecision.KEEP);
        }
    }

    @Nested
    @DisplayName("extractExternalId")
    class ExtractExternalIdTests {

        @Test
        @DisplayName("Should prefer structured ID from jobspy-js")
        void shouldPreferStructuredId() {
            String id = service.extractExternalId("in-6d3675aae7316bb9", "https://de.indeed.com/viewjob?jk=abc123");
            assertThat(id).isEqualTo("in-6d3675aae7316bb9");
        }

        @Test
        @DisplayName("Should fallback to jk param when id is null")
        void shouldFallbackToJkParam() {
            String id = service.extractExternalId(null, "https://de.indeed.com/viewjob?jk=abc123&from=search");
            assertThat(id).isEqualTo("abc123");
        }

        @Test
        @DisplayName("Should fallback to jk param when id is blank")
        void shouldFallbackToJkParamWhenBlank() {
            String id = service.extractExternalId("", "https://indeed.com/viewjob?from=search&jk=deadbeef42&tk=xyz");
            assertThat(id).isEqualTo("deadbeef42");
        }

        @Test
        @DisplayName("Should fallback to URL hash when no jk param")
        void shouldFallbackToHashWhenNoJk() {
            String url = "https://indeed.com/jobs/some-other-format/12345";
            String id = service.extractExternalId(null, url);
            assertThat(id).isEqualTo(Integer.toHexString(url.hashCode()));
        }

        @Test
        @DisplayName("Should produce consistent hash for same URL")
        void shouldProduceConsistentHash() {
            String url = "https://indeed.com/jobs/12345";
            String id1 = service.extractExternalId(null, url);
            String id2 = service.extractExternalId(null, url);
            assertThat(id1).isEqualTo(id2);
        }

        @Test
        @DisplayName("Should handle null URL with null id")
        void shouldHandleNullUrl() {
            String id = service.extractExternalId(null, null);
            assertThat(id).isEqualTo(Integer.toHexString("".hashCode()));
        }
    }
}
