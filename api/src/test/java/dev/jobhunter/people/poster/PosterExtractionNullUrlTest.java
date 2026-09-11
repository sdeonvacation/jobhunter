package dev.jobhunter.people.poster;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Regression tests for crawls failing with
 * {@code PropertyValueException: not-null property references a null or transient value :
 * dev.jobhunter.linkedin.OutreachContact.linkedinUrl}.
 *
 * <p>A poster with no LinkedIn URL (e.g. a Lever posting whose JSON has an owner name but no
 * profile link) must not produce an {@link OutreachContact}: {@code linkedinUrl} is
 * NOT NULL + UNIQUE, so persisting a null value makes Hibernate fail on the next auto-flush
 * and rolls back the whole crawl transaction.
 */
@ExtendWith(MockitoExtension.class)
class PosterExtractionNullUrlTest {

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
    void extractAndLink_posterWithoutLinkedinUrl_skipsContactAndLinksMetadataOnly() {
        JobPosting job = createJobPosting(AtsType.LEVER);
        PosterExtractor extractor = mock(PosterExtractor.class);
        PosterInfo info = new PosterInfo("Jane Doe", "Recruiter", null, null);

        when(registry.getExtractor(AtsType.LEVER)).thenReturn(Optional.of(extractor));
        when(extractor.extract(any(), any())).thenReturn(Optional.of(info));
        when(jobPostingRepository.save(any())).thenReturn(job);

        Optional<OutreachContact> result = service.extractAndLink(job, "<html>", Map.of());

        assertThat(result).isEmpty();
        verify(contactRepository, never()).save(any());
        verify(contactRepository, never()).findByLinkedinUrl(anyString());
        // Poster metadata is still recorded on the job
        assertThat(job.getPosterName()).isEqualTo("Jane Doe");
        assertThat(job.getPosterTitle()).isEqualTo("Recruiter");
        assertThat(job.getPosterLinkedinUrl()).isNull();
        assertThat(job.getPosterContactId()).isNull();
        verify(jobPostingRepository).save(job);
    }

    @Test
    void extractAndLink_posterWithBlankLinkedinUrl_skipsContact() {
        JobPosting job = createJobPosting(AtsType.LEVER);
        PosterExtractor extractor = mock(PosterExtractor.class);
        PosterInfo info = new PosterInfo("Jane Doe", "Recruiter", "   ", null);

        when(registry.getExtractor(AtsType.LEVER)).thenReturn(Optional.of(extractor));
        when(extractor.extract(any(), any())).thenReturn(Optional.of(info));
        when(jobPostingRepository.save(any())).thenReturn(job);

        Optional<OutreachContact> result = service.extractAndLink(job, "<html>", Map.of());

        assertThat(result).isEmpty();
        verify(contactRepository, never()).save(any());
        assertThat(job.getPosterContactId()).isNull();
    }

    @Test
    void extractAndLink_posterWithLinkedinUrl_createsContactAndLinksIt() {
        JobPosting job = createJobPosting(AtsType.LEVER);
        PosterExtractor extractor = mock(PosterExtractor.class);
        PosterInfo info = new PosterInfo("Jane Doe", "Recruiter", "https://linkedin.com/in/janedoe", null);
        OutreachContact savedContact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Jane Doe")
                .linkedinUrl("https://linkedin.com/in/janedoe")
                .build();

        when(registry.getExtractor(AtsType.LEVER)).thenReturn(Optional.of(extractor));
        when(extractor.extract(any(), any())).thenReturn(Optional.of(info));
        when(contactRepository.findByLinkedinUrl("https://linkedin.com/in/janedoe")).thenReturn(Optional.empty());
        when(contactRepository.save(any())).thenReturn(savedContact);
        when(jobPostingRepository.save(any())).thenReturn(job);

        Optional<OutreachContact> result = service.extractAndLink(job, "<html>", Map.of());

        assertThat(result).isPresent();
        verify(contactRepository).save(any());
        assertThat(job.getPosterLinkedinUrl()).isEqualTo("https://linkedin.com/in/janedoe");
        assertThat(job.getPosterContactId()).isEqualTo(savedContact.getId());
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
