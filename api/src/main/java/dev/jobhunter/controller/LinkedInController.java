package dev.jobhunter.controller;

import dev.jobhunter.linkedin.*;
import dev.jobhunter.linkedin.LinkedInNetworkingService.ConnectionResult;
import dev.jobhunter.linkedin.LinkedInNetworkingService.MessageResult;
import dev.jobhunter.linkedin.LinkedInProfileService.ProfileData;
import dev.jobhunter.model.Company;
import dev.jobhunter.repository.CompanyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
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
    private final RecruiterPostDetectionService recruiterPostDetectionService;

    public LinkedInController(LinkedInNetworkingService networkingService,
                              LinkedInProfileService profileService,
                              LinkedInCompanyEnricher companyEnricher,
                              HttpMcpClient httpMcpClient,
                              LinkedInRateLimiter rateLimiter,
                              CompanyRepository companyRepository,
                              RecruiterPostDetectionService recruiterPostDetectionService) {
        this.networkingService = networkingService;
        this.profileService = profileService;
        this.companyEnricher = companyEnricher;
        this.httpMcpClient = httpMcpClient;
        this.rateLimiter = rateLimiter;
        this.companyRepository = companyRepository;
        this.recruiterPostDetectionService = recruiterPostDetectionService;
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

    @PostMapping("/contacts/search-keywords")
    public ResponseEntity<List<OutreachContact>> searchByKeywords(@RequestBody KeywordSearchRequest request) {
        if (request.keywords() == null || request.keywords().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            List<OutreachContact> contacts = networkingService.searchByKeywords(
                    request.keywords(), request.location(), request.network());
            return ResponseEntity.ok(contacts);
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

    @PostMapping("/recruiter-post-check")
    public ResponseEntity<?> checkRecruiterPost(
            @RequestBody RecruiterPostCheckRequest request) {
        if (request.url() == null || request.url().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        // No pre-flight isSessionValid() probe: it costs a full LinkedIn browser
        // round-trip and reports transient sidecar timeouts as "invalid", which then
        // surfaced as a misleading 429. The service degrades to UNRESOLVED on its own.
        try {
            RecruiterPostDetectionService.RecruiterPostCheckResult result =
                    recruiterPostDetectionService.checkRecruiterPost(request.url(), request.force() != null && request.force());
            return ResponseEntity.ok(result);
        } catch (McpClientException e) {
            // Genuine MCP/session failure. 503 (not 429) so callers never confuse an
            // unreachable sidecar or an expired LinkedIn session with a rate limit.
            log.warn("Recruiter post check unavailable for {}: {}", request.url(), e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "linkedin_mcp_unavailable",
                            "detail", e.getMessage() == null ? "" : e.getMessage()));
        }
    }

    @PostMapping("/recruiter-post-check/batch-read")
    public ResponseEntity<List<RecruiterPostDetectionService.RecruiterPostCheckResult>> batchReadRecruiterPostChecks(
            @RequestBody BatchReadRequest request) {
        if (request.jobUrls() == null || request.jobUrls().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(recruiterPostDetectionService.readCached(request.jobUrls()));
    }

    // Request records

    public record ContactSearchRequest(UUID companyId, List<String> titleKeywords) {}

    public record KeywordSearchRequest(String keywords, String location, List<String> network) {}

    public record ConnectRequest(String note) {}

    public record MessageRequest(String message) {}

    public record RecruiterPostCheckRequest(String url, Boolean force) {}

    public record BatchReadRequest(List<String> jobUrls) {}
}
