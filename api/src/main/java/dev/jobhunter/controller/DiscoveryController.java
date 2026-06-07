package dev.jobhunter.controller;

import dev.jobhunter.dto.DiscoveryStatsDto;
import dev.jobhunter.dto.SourceQualityDto;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.DiscoveryEvent;
import dev.jobhunter.model.enums.CompanyStatus;
import dev.jobhunter.repository.CompanyRepository;
import dev.jobhunter.repository.DiscoveryEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/discovery")
public class DiscoveryController {

    private final DiscoveryEventRepository discoveryEventRepository;
    private final CompanyRepository companyRepository;

    public DiscoveryController(DiscoveryEventRepository discoveryEventRepository,
                               CompanyRepository companyRepository) {
        this.discoveryEventRepository = discoveryEventRepository;
        this.companyRepository = companyRepository;
    }

    @GetMapping("/stats")
    public ResponseEntity<DiscoveryStatsDto> getStats() {
        long totalDiscovered = discoveryEventRepository.count();
        long active = companyRepository.findByStatus(CompanyStatus.ACTIVE).size();
        long pending = companyRepository.findByStatus(CompanyStatus.PENDING_DETECTION).size();
        long unsupported = companyRepository.findByStatus(CompanyStatus.UNSUPPORTED).size();
        long resolved = active + unsupported; // companies that completed resolution

        DiscoveryStatsDto stats = new DiscoveryStatsDto(
                totalDiscovered,
                resolved,
                active,
                pending,
                unsupported
        );

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/source-quality")
    public ResponseEntity<List<SourceQualityDto>> getSourceQuality() {
        List<Company> activeCompanies = companyRepository.findByIsActiveTrue();

        // Group by discoveredVia and compute interview rates
        Map<String, List<Company>> bySource = activeCompanies.stream()
                .filter(c -> c.getDiscoveredVia() != null)
                .collect(Collectors.groupingBy(c -> c.getDiscoveredVia().name()));

        List<SourceQualityDto> quality = bySource.entrySet().stream()
                .map(entry -> {
                    long totalApps = entry.getValue().stream()
                            .mapToLong(Company::getTotalApplications).sum();
                    long totalInterviews = entry.getValue().stream()
                            .mapToLong(Company::getTotalInterviews).sum();
                    double rate = totalApps > 0 ? (double) totalInterviews / totalApps : 0.0;

                    return new SourceQualityDto(entry.getKey(), totalApps, totalInterviews, rate);
                })
                .toList();

        return ResponseEntity.ok(quality);
    }

    @GetMapping("/events")
    public Page<DiscoveryEventDto> getEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "discoveredAt"));

        List<DiscoveryEvent> allEvents = discoveryEventRepository.findAll();
        // Sort by discoveredAt desc
        allEvents.sort((a, b) -> b.getDiscoveredAt().compareTo(a.getDiscoveredAt()));

        int start = Math.min((int) pageable.getOffset(), allEvents.size());
        int end = Math.min(start + pageable.getPageSize(), allEvents.size());
        List<DiscoveryEvent> pageContent = allEvents.subList(start, end);

        List<DiscoveryEventDto> dtos = pageContent.stream()
                .map(e -> new DiscoveryEventDto(
                        e.getId(),
                        e.getCompanyName(),
                        e.getProvider(),
                        e.getSourceJobTitle(),
                        e.getSourceUrl(),
                        e.getDiscoveredAt(),
                        e.getOutcome() != null ? e.getOutcome().name() : null
                ))
                .toList();

        return new PageImpl<>(dtos, pageable, allEvents.size());
    }

    public record DiscoveryEventDto(
            java.util.UUID id,
            String companyName,
            String provider,
            String sourceJobTitle,
            String sourceUrl,
            java.time.LocalDateTime discoveredAt,
            String outcome
    ) {}
}
