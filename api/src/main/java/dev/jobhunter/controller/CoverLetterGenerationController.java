package dev.jobhunter.controller;

import dev.jobhunter.dto.CoverLetterGenerationDto;
import dev.jobhunter.dto.CoverLetterRequestDto;
import dev.jobhunter.dto.CoverLetterUpdateDto;
import dev.jobhunter.model.CoverLetter;
import dev.jobhunter.repository.CoverLetterRepository;
import dev.jobhunter.service.CoverLetterGenerationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class CoverLetterGenerationController {

    private final CoverLetterGenerationService coverLetterGenerationService;
    private final CoverLetterRepository coverLetterRepository;

    public CoverLetterGenerationController(CoverLetterGenerationService coverLetterGenerationService,
                                           CoverLetterRepository coverLetterRepository) {
        this.coverLetterGenerationService = coverLetterGenerationService;
        this.coverLetterRepository = coverLetterRepository;
    }

    @PostMapping("/{id}/cover-letter")
    public ResponseEntity<CoverLetterGenerationDto> generate(
            @PathVariable UUID id,
            @RequestBody(required = false) CoverLetterRequestDto request) {

        String tone = request != null ? request.tone() : null;
        String focus = request != null ? request.focus() : null;
        List<String> angles = request != null ? request.angles() : null;

        Optional<CoverLetter> result = coverLetterGenerationService.generate(id, tone, focus, angles);
        if (result.isEmpty()) {
            return ResponseEntity.unprocessableEntity().build();
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(result.get()));
    }

    @GetMapping("/{id}/cover-letters")
    public List<CoverLetterGenerationDto> listForJob(@PathVariable UUID id) {
        return coverLetterGenerationService.list(id).stream()
                .map(this::toDto)
                .toList();
    }

    @GetMapping("/{id}/cover-letter/latest")
    public ResponseEntity<CoverLetterGenerationDto> getLatest(@PathVariable UUID id) {
        Optional<CoverLetter> result = coverLetterGenerationService.getForJob(id);
        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDto(result.get()));
    }

    @PutMapping("/cover-letters/{coverId}")
    public ResponseEntity<CoverLetterGenerationDto> update(
            @PathVariable UUID coverId,
            @RequestBody CoverLetterUpdateDto body) {

        Optional<CoverLetter> result = coverLetterGenerationService.update(coverId, body.content());
        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDto(result.get()));
    }

    @DeleteMapping("/{id}/cover-letters/{coverId}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @PathVariable UUID coverId) {
        if (!coverLetterRepository.existsById(coverId)) {
            return ResponseEntity.notFound().build();
        }
        coverLetterRepository.deleteById(coverId);
        return ResponseEntity.noContent().build();
    }

    private CoverLetterGenerationDto toDto(CoverLetter cl) {
        return new CoverLetterGenerationDto(
                cl.getId(),
                cl.getJob().getId(),
                cl.getContent(),
                cl.getTone(),
                cl.getFocus(),
                cl.getAngles(),
                cl.getKeywordsMirrored(),
                cl.getVersion(),
                cl.getGeneratedAt(),
                cl.getEditedAt()
        );
    }
}
