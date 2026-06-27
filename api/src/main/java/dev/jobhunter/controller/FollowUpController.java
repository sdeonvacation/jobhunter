package dev.jobhunter.controller;

import dev.jobhunter.dto.FollowUpDto;
import dev.jobhunter.dto.FollowUpScheduleDto;
import dev.jobhunter.service.FollowUpCadenceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class FollowUpController {

    private final FollowUpCadenceService followUpCadenceService;

    public FollowUpController(FollowUpCadenceService followUpCadenceService) {
        this.followUpCadenceService = followUpCadenceService;
    }

    @PostMapping("/jobs/{id}/follow-up")
    public ResponseEntity<FollowUpDto> scheduleFollowUp(@PathVariable UUID id) {
        try {
            FollowUpDto dto = followUpCadenceService.scheduleFollowUp(id);
            return ResponseEntity.status(HttpStatus.CREATED).body(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/follow-ups")
    public FollowUpScheduleDto getFollowUps(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "10") int limit) {
        return followUpCadenceService.getSchedule(status, limit);
    }

    @PatchMapping("/follow-ups/{id}/sent")
    public ResponseEntity<FollowUpDto> markSent(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body) {
        try {
            String notes = body != null ? body.get("notes") : null;
            FollowUpDto dto = followUpCadenceService.markSent(id, notes);
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/follow-ups/{id}")
    public ResponseEntity<Void> cancelFollowUp(@PathVariable UUID id) {
        try {
            followUpCadenceService.cancelFollowUp(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/follow-ups/overdue")
    public List<FollowUpDto> getOverdue() {
        return followUpCadenceService.getOverdue();
    }
}
