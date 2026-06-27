package dev.jobhunter.controller;

import dev.jobhunter.dto.CoverLetterGenerationDto;
import dev.jobhunter.dto.CoverLetterRequestDto;
import dev.jobhunter.dto.CoverLetterUpdateDto;
import dev.jobhunter.model.CoverLetter;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.repository.CoverLetterRepository;
import dev.jobhunter.service.CoverLetterGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CoverLetterGenerationControllerTest {

    @Mock private CoverLetterGenerationService service;
    @Mock private CoverLetterRepository coverLetterRepository;

    private CoverLetterGenerationController controller;

    @BeforeEach
    void setUp() {
        controller = new CoverLetterGenerationController(service, coverLetterRepository);
    }

    private CoverLetter testCoverLetter(UUID jobId) {
        JobPosting job = JobPosting.builder().id(jobId).build();
        return CoverLetter.builder()
                .id(UUID.randomUUID())
                .job(job)
                .content("Dear Hiring Manager...")
                .tone("professional")
                .focus("backend")
                .angles(List.of("scalability"))
                .keywordsMirrored(List.of("Java", "Spring"))
                .version(1)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void generate_success_returns201() {
        UUID jobId = UUID.randomUUID();
        CoverLetter cl = testCoverLetter(jobId);
        CoverLetterRequestDto request = new CoverLetterRequestDto("enthusiastic", "APIs", List.of("REST"));

        when(service.generate(jobId, "enthusiastic", "APIs", List.of("REST")))
                .thenReturn(Optional.of(cl));

        ResponseEntity<CoverLetterGenerationDto> response = controller.generate(jobId, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().content()).isEqualTo("Dear Hiring Manager...");
        assertThat(response.getBody().tone()).isEqualTo("professional");
        assertThat(response.getBody().version()).isEqualTo(1);
    }

    @Test
    void generate_serviceReturnsEmpty_returns422() {
        UUID jobId = UUID.randomUUID();
        CoverLetterRequestDto request = new CoverLetterRequestDto("professional", null, null);

        when(service.generate(jobId, "professional", null, null)).thenReturn(Optional.empty());

        ResponseEntity<CoverLetterGenerationDto> response = controller.generate(jobId, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void generate_nullRequest_passesNulls() {
        UUID jobId = UUID.randomUUID();
        CoverLetter cl = testCoverLetter(jobId);

        when(service.generate(jobId, null, null, null)).thenReturn(Optional.of(cl));

        ResponseEntity<CoverLetterGenerationDto> response = controller.generate(jobId, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(service).generate(jobId, null, null, null);
    }

    @Test
    void listForJob_returnsAllVersions() {
        UUID jobId = UUID.randomUUID();
        CoverLetter cl1 = testCoverLetter(jobId);
        CoverLetter cl2 = testCoverLetter(jobId);

        when(service.list(jobId)).thenReturn(List.of(cl1, cl2));

        List<CoverLetterGenerationDto> result = controller.listForJob(jobId);

        assertThat(result).hasSize(2);
    }

    @Test
    void listForJob_noneExist_returnsEmptyList() {
        UUID jobId = UUID.randomUUID();
        when(service.list(jobId)).thenReturn(List.of());

        List<CoverLetterGenerationDto> result = controller.listForJob(jobId);

        assertThat(result).isEmpty();
    }

    @Test
    void getLatest_found_returns200() {
        UUID jobId = UUID.randomUUID();
        CoverLetter cl = testCoverLetter(jobId);

        when(service.getForJob(jobId)).thenReturn(Optional.of(cl));

        ResponseEntity<CoverLetterGenerationDto> response = controller.getLatest(jobId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().jobId()).isEqualTo(jobId);
    }

    @Test
    void getLatest_notFound_returns404() {
        UUID jobId = UUID.randomUUID();
        when(service.getForJob(jobId)).thenReturn(Optional.empty());

        ResponseEntity<CoverLetterGenerationDto> response = controller.getLatest(jobId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void update_success_returns200() {
        UUID coverId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        CoverLetter cl = testCoverLetter(jobId);
        CoverLetterUpdateDto body = new CoverLetterUpdateDto("updated content");

        when(service.update(coverId, "updated content")).thenReturn(Optional.of(cl));

        ResponseEntity<CoverLetterGenerationDto> response = controller.update(coverId, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void update_notFound_returns404() {
        UUID coverId = UUID.randomUUID();
        CoverLetterUpdateDto body = new CoverLetterUpdateDto("content");

        when(service.update(coverId, "content")).thenReturn(Optional.empty());

        ResponseEntity<CoverLetterGenerationDto> response = controller.update(coverId, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void generate_dtoPropagatesAllFields() {
        UUID jobId = UUID.randomUUID();
        LocalDateTime genAt = LocalDateTime.of(2025, 6, 15, 10, 30);
        LocalDateTime editAt = LocalDateTime.of(2025, 6, 15, 11, 0);

        JobPosting job = JobPosting.builder().id(jobId).build();
        UUID clId = UUID.randomUUID();
        CoverLetter cl = CoverLetter.builder()
                .id(clId)
                .job(job)
                .content("letter text")
                .tone("conversational")
                .focus("cloud native")
                .angles(List.of("AWS", "Terraform"))
                .keywordsMirrored(List.of("EKS", "Lambda"))
                .version(3)
                .generatedAt(genAt)
                .editedAt(editAt)
                .build();

        when(service.generate(eq(jobId), any(), any(), any())).thenReturn(Optional.of(cl));

        ResponseEntity<CoverLetterGenerationDto> response = controller.generate(
                jobId, new CoverLetterRequestDto("conversational", "cloud native", List.of("AWS", "Terraform")));

        CoverLetterGenerationDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.id()).isEqualTo(clId);
        assertThat(dto.jobId()).isEqualTo(jobId);
        assertThat(dto.content()).isEqualTo("letter text");
        assertThat(dto.tone()).isEqualTo("conversational");
        assertThat(dto.focus()).isEqualTo("cloud native");
        assertThat(dto.angles()).containsExactly("AWS", "Terraform");
        assertThat(dto.keywordsMirrored()).containsExactly("EKS", "Lambda");
        assertThat(dto.version()).isEqualTo(3);
        assertThat(dto.generatedAt()).isEqualTo(genAt);
        assertThat(dto.editedAt()).isEqualTo(editAt);
    }

    @Test
    void delete_exists_returns204() {
        UUID jobId = UUID.randomUUID();
        UUID coverId = UUID.randomUUID();

        when(coverLetterRepository.existsById(coverId)).thenReturn(true);

        ResponseEntity<Void> response = controller.delete(jobId, coverId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(coverLetterRepository).deleteById(coverId);
    }

    @Test
    void delete_notFound_returns404() {
        UUID jobId = UUID.randomUUID();
        UUID coverId = UUID.randomUUID();

        when(coverLetterRepository.existsById(coverId)).thenReturn(false);

        ResponseEntity<Void> response = controller.delete(jobId, coverId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(coverLetterRepository, never()).deleteById(any());
    }
}
