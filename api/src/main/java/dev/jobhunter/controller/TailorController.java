package dev.jobhunter.controller;

import dev.jobhunter.dto.*;
import dev.jobhunter.service.CoverLetterService;
import dev.jobhunter.service.ResumeTailoringService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class TailorController {

    private final ResumeTailoringService resumeTailoringService;
    private final CoverLetterService coverLetterService;

    public TailorController(ResumeTailoringService resumeTailoringService,
                            CoverLetterService coverLetterService) {
        this.resumeTailoringService = resumeTailoringService;
        this.coverLetterService = coverLetterService;
    }

    @PostMapping("/tailor/{jobId}")
    public ResponseEntity<TailoredResumeDto> tailorResume(
            @PathVariable UUID jobId,
            @RequestBody(required = false) TailorResumeRequest request) {

        String emphasis = request != null ? request.emphasis() : null;
        List<String> excludeSkills = request != null ? request.excludeSkills() : null;

        Optional<TailoredResumeDto> result = resumeTailoringService.tailor(jobId, emphasis, excludeSkills);
        if (result.isEmpty()) {
            return ResponseEntity.unprocessableEntity().build();
        }

        return ResponseEntity.ok(result.get());
    }

    @PostMapping("/cover-letter/{jobId}")
    public ResponseEntity<CoverLetterDto> generateCoverLetter(
            @PathVariable UUID jobId,
            @RequestBody(required = false) CoverLetterRequest request) {

        String tone = request != null ? request.tone() : null;
        String focus = request != null ? request.focus() : null;

        Optional<CoverLetterDto> result = coverLetterService.generate(jobId, tone, focus);
        if (result.isEmpty()) {
            return ResponseEntity.unprocessableEntity().build();
        }

        return ResponseEntity.ok(result.get());
    }
}
