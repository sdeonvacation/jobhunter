package dev.jobhunter.controller;

import dev.jobhunter.dto.InterviewPrepDto;
import dev.jobhunter.dto.InterviewStoryCreateDto;
import dev.jobhunter.dto.InterviewStoryDto;
import dev.jobhunter.service.InterviewPrepService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewPrepControllerTest {

    @Mock private InterviewPrepService interviewPrepService;

    private InterviewPrepController controller;

    @BeforeEach
    void setUp() {
        controller = new InterviewPrepController(interviewPrepService);
    }

    // --- prepareForJob ---

    @Test
    void prepareForJob_success_returns201() {
        UUID jobId = UUID.randomUUID();
        InterviewPrepDto dto = new InterviewPrepDto(
                UUID.randomUUID(), jobId, "Engineer", "Corp",
                List.of(Map.of("point", "test")), List.of(), Map.of(), LocalDateTime.now()
        );

        when(interviewPrepService.prepareForJob(jobId)).thenReturn(Optional.of(dto));

        ResponseEntity<InterviewPrepDto> response = controller.prepareForJob(jobId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().jobTitle()).isEqualTo("Engineer");
    }

    @Test
    void prepareForJob_serviceReturnsEmpty_returns422() {
        UUID jobId = UUID.randomUUID();
        when(interviewPrepService.prepareForJob(jobId)).thenReturn(Optional.empty());

        ResponseEntity<InterviewPrepDto> response = controller.prepareForJob(jobId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // --- getPrep ---

    @Test
    void getPrep_found_returns200() {
        UUID jobId = UUID.randomUUID();
        InterviewPrepDto dto = new InterviewPrepDto(
                UUID.randomUUID(), jobId, "Dev", "Acme",
                List.of(), List.of(), null, LocalDateTime.now()
        );

        when(interviewPrepService.getPrep(jobId)).thenReturn(Optional.of(dto));

        ResponseEntity<InterviewPrepDto> response = controller.getPrep(jobId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().companyName()).isEqualTo("Acme");
    }

    @Test
    void getPrep_notFound_returns404() {
        UUID jobId = UUID.randomUUID();
        when(interviewPrepService.getPrep(jobId)).thenReturn(Optional.empty());

        ResponseEntity<InterviewPrepDto> response = controller.getPrep(jobId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- listStories ---

    @Test
    void listStories_returnsList() {
        InterviewStoryDto story = new InterviewStoryDto(
                UUID.randomUUID(), "Sit", "Task", "Act", "Res", null,
                List.of("tag"), List.of("skill"), null, LocalDateTime.now(), LocalDateTime.now()
        );

        when(interviewPrepService.listStories()).thenReturn(List.of(story));

        List<InterviewStoryDto> result = controller.listStories();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).situation()).isEqualTo("Sit");
    }

    // --- createStory ---

    @Test
    void createStory_returns201() {
        InterviewStoryCreateDto createDto = new InterviewStoryCreateDto(
                "situation", "task", "action", "result", null, List.of(), List.of(), null
        );
        InterviewStoryDto responseDto = new InterviewStoryDto(
                UUID.randomUUID(), "situation", "task", "action", "result", null,
                List.of(), List.of(), null, LocalDateTime.now(), LocalDateTime.now()
        );

        when(interviewPrepService.addStory(createDto)).thenReturn(responseDto);

        ResponseEntity<InterviewStoryDto> response = controller.createStory(createDto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().situation()).isEqualTo("situation");
    }

    // --- getStory ---

    @Test
    void getStory_found_returns200() {
        UUID id = UUID.randomUUID();
        InterviewStoryDto dto = new InterviewStoryDto(
                id, "S", "T", "A", "R", "Ref",
                List.of(), List.of(), null, LocalDateTime.now(), LocalDateTime.now()
        );

        when(interviewPrepService.getStory(id)).thenReturn(Optional.of(dto));

        ResponseEntity<InterviewStoryDto> response = controller.getStory(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().id()).isEqualTo(id);
    }

    @Test
    void getStory_notFound_returns404() {
        UUID id = UUID.randomUUID();
        when(interviewPrepService.getStory(id)).thenReturn(Optional.empty());

        ResponseEntity<InterviewStoryDto> response = controller.getStory(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- updateStory ---

    @Test
    void updateStory_found_returns200() {
        UUID id = UUID.randomUUID();
        InterviewStoryCreateDto updateDto = new InterviewStoryCreateDto(
                "new", null, "act", "res", null, null, null, null
        );
        InterviewStoryDto responseDto = new InterviewStoryDto(
                id, "new", null, "act", "res", null,
                null, null, null, LocalDateTime.now(), LocalDateTime.now()
        );

        when(interviewPrepService.updateStory(id, updateDto)).thenReturn(Optional.of(responseDto));

        ResponseEntity<InterviewStoryDto> response = controller.updateStory(id, updateDto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().situation()).isEqualTo("new");
    }

    @Test
    void updateStory_notFound_returns404() {
        UUID id = UUID.randomUUID();
        InterviewStoryCreateDto updateDto = new InterviewStoryCreateDto(
                "s", null, "a", "r", null, null, null, null
        );

        when(interviewPrepService.updateStory(id, updateDto)).thenReturn(Optional.empty());

        ResponseEntity<InterviewStoryDto> response = controller.updateStory(id, updateDto);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- deleteStory ---

    @Test
    void deleteStory_exists_returns204() {
        UUID id = UUID.randomUUID();
        when(interviewPrepService.deleteStory(id)).thenReturn(true);

        ResponseEntity<Void> response = controller.deleteStory(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteStory_notFound_returns404() {
        UUID id = UUID.randomUUID();
        when(interviewPrepService.deleteStory(id)).thenReturn(false);

        ResponseEntity<Void> response = controller.deleteStory(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
