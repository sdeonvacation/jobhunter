package dev.jobhub.controller;

import dev.jobhub.dto.TailoredResumeDto;
import dev.jobhub.dto.CoverLetterDto;
import dev.jobhub.dto.TailorResumeRequest;
import dev.jobhub.dto.CoverLetterRequest;
import dev.jobhub.service.CoverLetterService;
import dev.jobhub.service.ResumeTailoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TailorControllerTest {

    @Mock private ResumeTailoringService resumeTailoringService;
    @Mock private CoverLetterService coverLetterService;

    private TailorController controller;

    @BeforeEach
    void setUp() {
        controller = new TailorController(resumeTailoringService, coverLetterService);
    }

    @Test
    void tailorResume_success_returnsOk() {
        UUID jobId = UUID.randomUUID();
        TailoredResumeDto dto = new TailoredResumeDto(
                jobId, "Dev", "Acme", "Summary", List.of("Java"), List.of("Built APIs"), "backend"
        );

        when(resumeTailoringService.tailor(eq(jobId), eq("backend"), eq(List.of("CSS"))))
                .thenReturn(Optional.of(dto));

        var response = controller.tailorResume(jobId, new TailorResumeRequest("backend", List.of("CSS")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().tailoredSummary()).isEqualTo("Summary");
    }

    @Test
    void tailorResume_serviceReturnsEmpty_returns422() {
        UUID jobId = UUID.randomUUID();
        when(resumeTailoringService.tailor(eq(jobId), any(), any())).thenReturn(Optional.empty());

        var response = controller.tailorResume(jobId, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void tailorResume_nullRequest_passesNulls() {
        UUID jobId = UUID.randomUUID();
        TailoredResumeDto dto = new TailoredResumeDto(
                jobId, "Dev", "Co", "S", List.of(), List.of(), null
        );

        when(resumeTailoringService.tailor(jobId, null, null)).thenReturn(Optional.of(dto));

        var response = controller.tailorResume(jobId, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void generateCoverLetter_success_returnsOk() {
        UUID jobId = UUID.randomUUID();
        CoverLetterDto dto = new CoverLetterDto(jobId, "Eng", "Corp", "Dear...", "enthusiastic");

        when(coverLetterService.generate(eq(jobId), eq("enthusiastic"), eq("leadership")))
                .thenReturn(Optional.of(dto));

        var response = controller.generateCoverLetter(jobId, new CoverLetterRequest("enthusiastic", "leadership"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().content()).isEqualTo("Dear...");
        assertThat(response.getBody().tone()).isEqualTo("enthusiastic");
    }

    @Test
    void generateCoverLetter_serviceReturnsEmpty_returns422() {
        UUID jobId = UUID.randomUUID();
        when(coverLetterService.generate(eq(jobId), any(), any())).thenReturn(Optional.empty());

        var response = controller.generateCoverLetter(jobId, new CoverLetterRequest("casual", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
