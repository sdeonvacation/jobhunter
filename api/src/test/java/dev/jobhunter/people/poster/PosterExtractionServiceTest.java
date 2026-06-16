package dev.jobhunter.people.poster;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.people.model.enums.Seniority;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PosterExtractionServiceTest {

    @Mock
    private PosterExtractorRegistry registry;
    @Mock
    private OutreachContactRepository contactRepository;
    @Mock
    private JobPostingRepository jobPostingRepository;

    private PosterExtractionService service;

    @BeforeEach
    void setUp() {
        service = new PosterExtractionService(registry, contactRepository, jobPostingRepository);
    }

    @Test
    void extractAndLink_successfulExtraction_createsContactAndLinksToJob() {
        JobPosting job = createJobPosting(AtsType.GREENHOUSE);
        PosterExtractor extractor = mock(PosterExtractor.class);
        PosterInfo info = new PosterInfo("Jane Doe", "Recruiter", "https://linkedin.com/in/janedoe", null);
        OutreachContact savedContact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Jane Doe")
                .build();

        when(registry.getExtractor(AtsType.GREENHOUSE)).thenReturn(Optional.of(extractor));
        when(extractor.extract(any(), any())).thenReturn(Optional.of(info));
        when(contactRepository.findByLinkedinUrl("https://linkedin.com/in/janedoe")).thenReturn(Optional.empty());
        when(contactRepository.save(any())).thenReturn(savedContact);
        when(jobPostingRepository.save(any())).thenReturn(job);

        Optional<OutreachContact> result = service.extractAndLink(job, "<html>", Map.of());

        assertThat(result).isPresent();
        assertThat(result.get().getPersonName()).isEqualTo("Jane Doe");
        verify(jobPostingRepository).save(job);
        assertThat(job.getPosterName()).isEqualTo("Jane Doe");
        assertThat(job.getPosterTitle()).isEqualTo("Recruiter");
        assertThat(job.getPosterLinkedinUrl()).isEqualTo("https://linkedin.com/in/janedoe");
        assertThat(job.getPosterContactId()).isEqualTo(savedContact.getId());
    }

    @Test
    void extractAndLink_existingContact_updatesAndLinks() {
        JobPosting job = createJobPosting(AtsType.GREENHOUSE);
        PosterExtractor extractor = mock(PosterExtractor.class);
        PosterInfo info = new PosterInfo("Jane Doe", "Senior Recruiter", "https://linkedin.com/in/janedoe", null);
        OutreachContact existing = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Jane Doe")
                .linkedinUrl("https://linkedin.com/in/janedoe")
                .build();

        when(registry.getExtractor(AtsType.GREENHOUSE)).thenReturn(Optional.of(extractor));
        when(extractor.extract(any(), any())).thenReturn(Optional.of(info));
        when(contactRepository.findByLinkedinUrl("https://linkedin.com/in/janedoe")).thenReturn(Optional.of(existing));
        when(contactRepository.save(any())).thenReturn(existing);
        when(jobPostingRepository.save(any())).thenReturn(job);

        Optional<OutreachContact> result = service.extractAndLink(job, "<html>", Map.of());

        assertThat(result).isPresent();
        // Verifies deduplication - no new contact created
        ArgumentCaptor<OutreachContact> captor = ArgumentCaptor.forClass(OutreachContact.class);
        verify(contactRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(existing.getId());
    }

    @Test
    void extractAndLink_noExtractor_returnsEmpty() {
        JobPosting job = createJobPosting(AtsType.WORKDAY);

        when(registry.getExtractor(AtsType.WORKDAY)).thenReturn(Optional.empty());

        Optional<OutreachContact> result = service.extractAndLink(job, "<html>", Map.of());

        assertThat(result).isEmpty();
        verify(contactRepository, never()).save(any());
    }

    @Test
    void extractAndLink_extractionFails_returnsEmpty() {
        JobPosting job = createJobPosting(AtsType.GREENHOUSE);
        PosterExtractor extractor = mock(PosterExtractor.class);

        when(registry.getExtractor(AtsType.GREENHOUSE)).thenReturn(Optional.of(extractor));
        when(extractor.extract(any(), any())).thenReturn(Optional.empty());

        Optional<OutreachContact> result = service.extractAndLink(job, "<html>", Map.of());

        assertThat(result).isEmpty();
        verify(contactRepository, never()).save(any());
    }

    @Test
    void extractAndLink_exceptionThrown_returnsEmptyAndDoesNotPropagate() {
        JobPosting job = createJobPosting(AtsType.GREENHOUSE);
        PosterExtractor extractor = mock(PosterExtractor.class);

        when(registry.getExtractor(AtsType.GREENHOUSE)).thenReturn(Optional.of(extractor));
        when(extractor.extract(any(), any())).thenThrow(new RuntimeException("Parse error"));

        Optional<OutreachContact> result = service.extractAndLink(job, "<html>", Map.of());

        assertThat(result).isEmpty();
    }

    @Test
    void extractAndLink_nullEndpoint_returnsEmpty() {
        JobPosting job = JobPosting.builder().id(UUID.randomUUID()).build();

        Optional<OutreachContact> result = service.extractAndLink(job, "<html>", Map.of());

        assertThat(result).isEmpty();
    }

    @Test
    void inferSeniority_seniorTitle() {
        assertThat(service.inferSeniority("Senior Software Engineer")).isEqualTo(Seniority.SENIOR);
        assertThat(service.inferSeniority("Sr. Recruiter")).isEqualTo(Seniority.SENIOR);
        assertThat(service.inferSeniority("Lead Engineer")).isEqualTo(Seniority.SENIOR);
    }

    @Test
    void inferSeniority_staffTitle() {
        assertThat(service.inferSeniority("Staff Engineer")).isEqualTo(Seniority.STAFF);
        assertThat(service.inferSeniority("Principal Architect")).isEqualTo(Seniority.STAFF);
    }

    @Test
    void inferSeniority_vpTitle() {
        assertThat(service.inferSeniority("VP of Engineering")).isEqualTo(Seniority.VP);
        assertThat(service.inferSeniority("Vice President Talent")).isEqualTo(Seniority.VP);
    }

    @Test
    void inferSeniority_cLevelTitle() {
        assertThat(service.inferSeniority("CTO")).isEqualTo(Seniority.C_LEVEL);
        assertThat(service.inferSeniority("Chief Technology Officer")).isEqualTo(Seniority.C_LEVEL);
    }

    @Test
    void inferSeniority_juniorTitle() {
        assertThat(service.inferSeniority("Junior Recruiter")).isEqualTo(Seniority.JUNIOR);
        assertThat(service.inferSeniority("Intern")).isEqualTo(Seniority.JUNIOR);
    }

    @Test
    void inferSeniority_midTitle() {
        assertThat(service.inferSeniority("Software Engineer")).isEqualTo(Seniority.MID);
        assertThat(service.inferSeniority("Recruiter")).isEqualTo(Seniority.MID);
    }

    @Test
    void inferSeniority_nullOrBlank() {
        assertThat(service.inferSeniority(null)).isEqualTo(Seniority.UNKNOWN);
        assertThat(service.inferSeniority("")).isEqualTo(Seniority.UNKNOWN);
        assertThat(service.inferSeniority("   ")).isEqualTo(Seniority.UNKNOWN);
    }

    @Test
    void afterJobPersisted_delegatesToExtractAndLink() {
        JobPosting job = createJobPosting(AtsType.GREENHOUSE);
        when(registry.getExtractor(AtsType.GREENHOUSE)).thenReturn(Optional.empty());

        // Should not throw
        service.afterJobPersisted(job, "<html>", Map.of());
    }

    @Test
    void order_returns10() {
        assertThat(service.order()).isEqualTo(10);
    }

    private JobPosting createJobPosting(AtsType atsType) {
        CareerEndpoint endpoint = CareerEndpoint.builder()
                .id(UUID.randomUUID())
                .atsType(atsType)
                .build();
        Company company = Company.builder()
                .id(UUID.randomUUID())
                .name("Test Corp")
                .build();
        return JobPosting.builder()
                .id(UUID.randomUUID())
                .endpoint(endpoint)
                .company(company)
                .build();
    }
}
