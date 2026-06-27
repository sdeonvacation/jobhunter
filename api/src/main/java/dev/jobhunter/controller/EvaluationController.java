package dev.jobhunter.controller;

import dev.jobhunter.dto.EvaluationBlocksDto;
import dev.jobhunter.dto.EvaluationDto;
import dev.jobhunter.service.EvaluationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping("/{id}/evaluate")
    public ResponseEntity<EvaluationDto> evaluate(
            @PathVariable UUID id,
            @RequestBody(required = false) EvaluationBlocksDto request) {

        var blocks = (request != null) ? request.blocks() : null;
        EvaluationDto result = evaluationService.evaluate(id, blocks);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/{id}/evaluation")
    public EvaluationDto getEvaluation(@PathVariable UUID id) {
        return evaluationService.getEvaluation(id);
    }

    @DeleteMapping("/{id}/evaluation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEvaluation(@PathVariable UUID id) {
        evaluationService.deleteEvaluation(id);
    }
}
