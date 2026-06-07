package dev.jobhunter.controller;

import dev.jobhunter.linkedin.*;
import dev.jobhunter.linkedin.LinkedInNetworkingService.ConnectionResult;
import dev.jobhunter.linkedin.LinkedInNetworkingService.MessageResult;
import dev.jobhunter.linkedin.LinkedInProfileService.ProfileData;
import dev.jobhunter.model.Company;
import dev.jobhunter.repository.CompanyRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/linkedin")
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class LinkedInController {

    private final LinkedInNetworkingService networkingService;
    private final LinkedInProfileService profileService;
    private final LinkedInCompanyEnricher companyEnricher;
    private final HttpMcpClient httpMcpClient;
    private final LinkedInRateLimiter rateLimiter;
    private final CompanyRepository companyRepository;

    public LinkedInController(LinkedInNetworkingService networkingService,
                              LinkedInProfileService profileService,
                              LinkedInCompanyEnricher companyEnricher,
                              HttpMcpClient httpMcpClient,
                              LinkedInRateLimiter rateLimiter,
                              CompanyRepository companyRepository) {
        this.networkingService = networkingService;
        this.profileService = profileService;
        this.companyEnricher = companyEnricher;
        this.httpMcpClient = httpMcpClient;
        this.rateLimiter = rateLimiter;
        this.companyRepository = companyRepository;
    }

    @PostMapping("/contacts/search")
    public ResponseEntity<List<OutreachContact>> searchContacts(@RequestBody ContactSearchRequest request) {
        if (request.companyId() == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            List<OutreachContact> contacts = networkingService.findContacts(
                    request.companyId(), request.titleKeywords());
            return ResponseEntity.ok(contacts);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @PostMapping("/contacts/{id}/connect")
    public ResponseEntity<ConnectionResult> connect(@PathVariable UUID id,
                                                    @RequestBody ConnectRequest request) {
        try {
            ConnectionResult result = networkingService.connect(id, request.note());
            return switch (result.status()) {
                case SENT -> ResponseEntity.ok(result);
                case DAILY_LIMIT_REACHED -> ResponseEntity.status(429).body(result);
                case ALREADY_CONNECTED -> ResponseEntity.status(409).body(result);
                case FAILED -> ResponseEntity.internalServerError().body(result);
            };
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/contacts/{id}/message")
    public ResponseEntity<MessageResult> sendMessage(@PathVariable UUID id,
                                                     @RequestBody MessageRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            MessageResult result = networkingService.sendMessage(id, request.message());
            return switch (result.status()) {
                case SENT -> ResponseEntity.ok(result);
                case COOLDOWN_ACTIVE -> ResponseEntity.status(429).body(result);
                case NOT_CONNECTED -> ResponseEntity.status(409).body(result);
                case FAILED -> ResponseEntity.internalServerError().body(result);
            };
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/profile")
    public ResponseEntity<ProfileData> getProfile(@RequestParam String url) {
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        ProfileData profile = profileService.getProfile(url);
        if (profile == null) {
            return ResponseEntity.status(429).build();
        }
        return ResponseEntity.ok(profile);
    }

    @PostMapping("/enrich/{companyId}")
    public ResponseEntity<Void> enrichCompany(@PathVariable UUID companyId) {
        Company company = companyRepository.findById(companyId).orElse(null);
        if (company == null) {
            return ResponseEntity.notFound().build();
        }
        companyEnricher.enrich(company);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = Map.of(
                "sessionValid", httpMcpClient.isSessionValid(),
                "remainingTokens", Map.of(
                        "search", rateLimiter.getRemainingTokens(ToolCategory.SEARCH),
                        "profile", rateLimiter.getRemainingTokens(ToolCategory.PROFILE),
                        "action", rateLimiter.getRemainingTokens(ToolCategory.ACTION)
                )
        );
        return ResponseEntity.ok(status);
    }

    @GetMapping("/contacts/remaining")
    public ResponseEntity<Map<String, Integer>> getDailyConnectionsRemaining() {
        return ResponseEntity.ok(
                Map.of("remaining", networkingService.getDailyConnectionsRemaining()));
    }

    // Request records

    public record ContactSearchRequest(UUID companyId, List<String> titleKeywords) {}

    public record ConnectRequest(String note) {}

    public record MessageRequest(String message) {}
}
