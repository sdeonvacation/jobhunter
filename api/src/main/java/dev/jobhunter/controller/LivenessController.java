package dev.jobhunter.controller;

import dev.jobhunter.dto.LivenessResultDto;
import dev.jobhunter.service.LivenessCheckService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class LivenessController {

    private final LivenessCheckService livenessCheckService;

    public LivenessController(LivenessCheckService livenessCheckService) {
        this.livenessCheckService = livenessCheckService;
    }

    @PostMapping("/{id}/liveness-check")
    public ResponseEntity<LivenessResultDto> checkLiveness(@PathVariable UUID id) {
        LivenessResultDto result = livenessCheckService.checkLiveness(id);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/liveness")
    public ResponseEntity<LivenessResultDto> getLivenessStatus(@PathVariable UUID id) {
        LivenessResultDto result = livenessCheckService.getStatus(id);
        return ResponseEntity.ok(result);
    }
}
