package dev.jobhunter.controller;

import dev.jobhunter.dto.InterviewPrepDto;
import dev.jobhunter.dto.InterviewStoryCreateDto;
import dev.jobhunter.dto.InterviewStoryDto;
import dev.jobhunter.service.InterviewPrepService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class InterviewPrepController {

    private final InterviewPrepService interviewPrepService;

    public InterviewPrepController(InterviewPrepService interviewPrepService) {
        this.interviewPrepService = interviewPrepService;
    }

    @PostMapping("/jobs/{id}/interview-prep")
    public ResponseEntity<InterviewPrepDto> prepareForJob(@PathVariable UUID id) {
        return interviewPrepService.prepareForJob(id)
                .map(dto -> ResponseEntity.status(HttpStatus.CREATED).body(dto))
                .orElse(ResponseEntity.unprocessableEntity().build());
    }

    @GetMapping("/jobs/{id}/interview-prep")
    public ResponseEntity<InterviewPrepDto> getPrep(@PathVariable UUID id) {
        return interviewPrepService.getPrep(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/interview-stories")
    public List<InterviewStoryDto> listStories() {
        return interviewPrepService.listStories();
    }

    @PostMapping("/interview-stories")
    public ResponseEntity<InterviewStoryDto> createStory(@RequestBody InterviewStoryCreateDto dto) {
        InterviewStoryDto created = interviewPrepService.addStory(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/interview-stories/{id}")
    public ResponseEntity<InterviewStoryDto> getStory(@PathVariable UUID id) {
        return interviewPrepService.getStory(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/interview-stories/{id}")
    public ResponseEntity<InterviewStoryDto> updateStory(@PathVariable UUID id,
                                                         @RequestBody InterviewStoryCreateDto dto) {
        return interviewPrepService.updateStory(id, dto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/interview-stories/{id}")
    public ResponseEntity<Void> deleteStory(@PathVariable UUID id) {
        if (interviewPrepService.deleteStory(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
