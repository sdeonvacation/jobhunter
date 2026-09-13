package dev.jobhunter.controller;

import dev.jobhunter.controller.AdminController.RefilterResult;
import dev.jobhunter.filter.FilterResult;
import dev.jobhunter.filter.LanguageFilter;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.FilterDecision;
import dev.jobhunter.repository.JobPostingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerRefilterLanguageTest {

    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private LanguageFilter languageFilter;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(null, null, null, null, null, null, null,
                null, null, null, jobPostingRepository, Optional.empty(), List.of(), languageFilter, List.of(), List.of());
    }

    @Test
    @DisplayName("refilterLanguage filters non-English jobs and persists changes")
    void refilterLanguage_filtersAndPersists() {
        JobPosting englishJob = createJob("Software Engineer", "Build microservices in Java");
        JobPosting germanJob = createJob("Entwickler", "Wir suchen einen erfahrenen Entwickler");

        when(jobPostingRepository.findActiveKeptJobsWithDescription())
                .thenReturn(List.of(englishJob, germanJob));
        when(languageFilter.filter("Software Engineer", "Build microservices in Java"))
                .thenReturn(FilterResult.keep());
        when(languageFilter.filter("Entwickler", "Wir suchen einen erfahrenen Entwickler"))
                .thenReturn(FilterResult.skip("German detected"));

        ResponseEntity<RefilterResult> response = controller.refilterLanguage(false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RefilterResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.evaluated()).isEqualTo(2);
        assertThat(result.filtered()).isEqualTo(1);
        assertThat(result.kept()).isEqualTo(1);
        assertThat(result.promoted()).isEqualTo(0);
        assertThat(result.dryRun()).isFalse();

        verify(jobPostingRepository).save(germanJob);
        assertThat(germanJob.getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
        assertThat(germanJob.getFilterReason()).isEqualTo("German detected");
        verify(jobPostingRepository, never()).save(englishJob);
    }

    @Test
    @DisplayName("refilterLanguage dryRun does not persist changes")
    void refilterLanguage_dryRun_doesNotPersist() {
        JobPosting germanJob = createJob("Entwickler", "Wir suchen einen erfahrenen Entwickler");

        when(jobPostingRepository.findActiveKeptJobsWithDescription())
                .thenReturn(List.of(germanJob));
        when(languageFilter.filter("Entwickler", "Wir suchen einen erfahrenen Entwickler"))
                .thenReturn(FilterResult.skip("German detected"));

        ResponseEntity<RefilterResult> response = controller.refilterLanguage(true);

        RefilterResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.evaluated()).isEqualTo(1);
        assertThat(result.filtered()).isEqualTo(1);
        assertThat(result.kept()).isEqualTo(0);
        assertThat(result.dryRun()).isTrue();

        verify(jobPostingRepository, never()).save(any());
        assertThat(germanJob.getLanguageFilter()).isNotEqualTo(FilterDecision.SKIP);
    }

    @Test
    @DisplayName("refilterLanguage with no jobs returns zeros")
    void refilterLanguage_noJobs_returnsZeros() {
        when(jobPostingRepository.findActiveKeptJobsWithDescription())
                .thenReturn(List.of());

        ResponseEntity<RefilterResult> response = controller.refilterLanguage(false);

        RefilterResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.evaluated()).isEqualTo(0);
        assertThat(result.filtered()).isEqualTo(0);
        assertThat(result.kept()).isEqualTo(0);
    }

    @Test
    @DisplayName("refilterLanguage when all jobs pass keeps all")
    void refilterLanguage_allPass_keepsAll() {
        JobPosting job1 = createJob("Engineer", "Java Spring Boot");
        JobPosting job2 = createJob("Developer", "React TypeScript");

        when(jobPostingRepository.findActiveKeptJobsWithDescription())
                .thenReturn(List.of(job1, job2));
        when(languageFilter.filter(anyString(), anyString()))
                .thenReturn(FilterResult.keep());

        ResponseEntity<RefilterResult> response = controller.refilterLanguage(false);

        RefilterResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.evaluated()).isEqualTo(2);
        assertThat(result.filtered()).isEqualTo(0);
        assertThat(result.kept()).isEqualTo(2);
        verify(jobPostingRepository, never()).save(any());
    }

    @Test
    @DisplayName("refilterLanguage promotes stale non-English SKIP that now keeps")
    void refilterLanguage_promotesStaleSkip() {
        JobPosting staleSkip = createSkippedJob("Software Engineer", "Build microservices in Java");

        when(jobPostingRepository.findActiveKeptJobsWithDescription()).thenReturn(List.of());
        when(jobPostingRepository.findActiveLanguageSkippedJobsWithDescription())
                .thenReturn(List.of(staleSkip));
        when(languageFilter.filter("Software Engineer", "Build microservices in Java"))
                .thenReturn(FilterResult.keep());

        ResponseEntity<RefilterResult> response = controller.refilterLanguage(false);

        RefilterResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.evaluated()).isEqualTo(1);
        assertThat(result.filtered()).isEqualTo(0);
        assertThat(result.kept()).isEqualTo(1);
        assertThat(result.promoted()).isEqualTo(1);

        verify(jobPostingRepository).save(staleSkip);
        assertThat(staleSkip.getLanguageFilter()).isEqualTo(FilterDecision.KEEP);
        assertThat(staleSkip.getFilterReason()).isNull();
    }

    @Test
    @DisplayName("refilterLanguage demotes a KEEP job that now skips")
    void refilterLanguage_demotesKeptJob() {
        JobPosting nowGerman = createJob("Entwickler", "Wir suchen einen erfahrenen Entwickler");

        when(jobPostingRepository.findActiveKeptJobsWithDescription()).thenReturn(List.of(nowGerman));
        when(languageFilter.filter("Entwickler", "Wir suchen einen erfahrenen Entwickler"))
                .thenReturn(FilterResult.skip("German detected"));

        ResponseEntity<RefilterResult> response = controller.refilterLanguage(false);

        RefilterResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.evaluated()).isEqualTo(1);
        assertThat(result.filtered()).isEqualTo(1);
        assertThat(result.kept()).isEqualTo(0);
        assertThat(result.promoted()).isEqualTo(0);

        verify(jobPostingRepository).save(nowGerman);
        assertThat(nowGerman.getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
        assertThat(nowGerman.getFilterReason()).isEqualTo("German detected");
    }

    @Test
    @DisplayName("refilterLanguage keeps a stale SKIP that still skips and refreshes its reason")
    void refilterLanguage_staleSkipStillSkips() {
        JobPosting staleSkip = createSkippedJob("Entwickler", "Wir suchen einen erfahrenen Entwickler");

        when(jobPostingRepository.findActiveKeptJobsWithDescription()).thenReturn(List.of());
        when(jobPostingRepository.findActiveLanguageSkippedJobsWithDescription())
                .thenReturn(List.of(staleSkip));
        when(languageFilter.filter("Entwickler", "Wir suchen einen erfahrenen Entwickler"))
                .thenReturn(FilterResult.skip("Dutch detected"));

        ResponseEntity<RefilterResult> response = controller.refilterLanguage(false);

        RefilterResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.evaluated()).isEqualTo(1);
        assertThat(result.filtered()).isEqualTo(1);
        assertThat(result.kept()).isEqualTo(0);
        assertThat(result.promoted()).isEqualTo(0);

        verify(jobPostingRepository).save(staleSkip);
        assertThat(staleSkip.getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
        assertThat(staleSkip.getFilterReason()).isEqualTo("Dutch detected");
    }

    @Test
    @DisplayName("refilterLanguage dryRun reports promoted counts but persists nothing")
    void refilterLanguage_dryRun_countsPromotionsWithoutPersisting() {
        JobPosting nowSkip = createJob("Entwickler", "Wir suchen einen erfahrenen Entwickler");
        JobPosting promoted = createSkippedJob("Software Engineer", "Build microservices in Java");

        when(jobPostingRepository.findActiveKeptJobsWithDescription()).thenReturn(List.of(nowSkip));
        when(jobPostingRepository.findActiveLanguageSkippedJobsWithDescription())
                .thenReturn(List.of(promoted));
        when(languageFilter.filter("Entwickler", "Wir suchen einen erfahrenen Entwickler"))
                .thenReturn(FilterResult.skip("German detected"));
        when(languageFilter.filter("Software Engineer", "Build microservices in Java"))
                .thenReturn(FilterResult.keep());

        ResponseEntity<RefilterResult> response = controller.refilterLanguage(true);

        RefilterResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.evaluated()).isEqualTo(2);
        assertThat(result.filtered()).isEqualTo(1);
        assertThat(result.kept()).isEqualTo(1);
        assertThat(result.promoted()).isEqualTo(1);
        assertThat(result.dryRun()).isTrue();

        verify(jobPostingRepository, never()).save(any());
        assertThat(nowSkip.getLanguageFilter()).isEqualTo(FilterDecision.KEEP);
        assertThat(promoted.getLanguageFilter()).isEqualTo(FilterDecision.SKIP);
        assertThat(promoted.getFilterReason()).isEqualTo("non-English JD (dutch)");
    }

    @Test
    @DisplayName("refilterLanguage counts only SKIP-to-KEEP transitions as promoted")
    void refilterLanguage_promotedCountCountsOnlyTransitions() {
        JobPosting promotes = createSkippedJob("Software Engineer", "Build microservices in Java");
        JobPosting staysSkipped = createSkippedJob("Entwickler", "Wir suchen einen erfahrenen Entwickler");

        when(jobPostingRepository.findActiveKeptJobsWithDescription()).thenReturn(List.of());
        when(jobPostingRepository.findActiveLanguageSkippedJobsWithDescription())
                .thenReturn(List.of(promotes, staysSkipped));
        when(languageFilter.filter("Software Engineer", "Build microservices in Java"))
                .thenReturn(FilterResult.keep());
        when(languageFilter.filter("Entwickler", "Wir suchen einen erfahrenen Entwickler"))
                .thenReturn(FilterResult.skip("German detected"));

        ResponseEntity<RefilterResult> response = controller.refilterLanguage(false);

        RefilterResult result = response.getBody();
        assertThat(result).isNotNull();
        assertThat(result.evaluated()).isEqualTo(2);
        assertThat(result.kept()).isEqualTo(1);
        assertThat(result.filtered()).isEqualTo(1);
        assertThat(result.promoted()).isEqualTo(1);
    }

    private JobPosting createJob(String title, String description) {
        JobPosting job = new JobPosting();
        job.setTitle(title);
        job.setDescription(description);
        job.setLanguageFilter(FilterDecision.KEEP);
        return job;
    }

    private JobPosting createSkippedJob(String title, String description) {
        JobPosting job = new JobPosting();
        job.setTitle(title);
        job.setDescription(description);
        job.setLanguageFilter(FilterDecision.SKIP);
        job.setFilterReason("non-English JD (dutch)");
        return job;
    }
}
