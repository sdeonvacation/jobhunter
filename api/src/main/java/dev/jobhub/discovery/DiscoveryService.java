package dev.jobhub.discovery;

import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.model.Company;
import dev.jobhub.model.DiscoveryEvent;
import dev.jobhub.model.enums.*;
import dev.jobhub.repository.CareerEndpointRepository;
import dev.jobhub.repository.CompanyRepository;
import dev.jobhub.repository.DiscoveryEventRepository;
import dev.jobhub.repository.ResolutionResultRepository;
import dev.jobhub.resolution.AtsDetector;
import dev.jobhub.resolution.CompositeEndpointResolver;
import dev.jobhub.resolution.ResolutionResultDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates the discovery pipeline:
 * providers → normalize → check registry → resolve endpoints → detect ATS → register
 */
@Slf4j
@Service
public class DiscoveryService {

    private final List<DiscoveryProvider> providers;
    private final CompanyNormalizer companyNormalizer;
    private final CompanyRepository companyRepository;
    private final CareerEndpointRepository careerEndpointRepository;
    private final DiscoveryEventRepository discoveryEventRepository;
    private final ResolutionResultRepository resolutionResultRepository;
    private final CompositeEndpointResolver endpointResolver;
    private final AtsDetector atsDetector;
    private final DiscoveryProperties properties;

    public DiscoveryService(
            List<DiscoveryProvider> providers,
            CompanyNormalizer companyNormalizer,
            CompanyRepository companyRepository,
            CareerEndpointRepository careerEndpointRepository,
            DiscoveryEventRepository discoveryEventRepository,
            ResolutionResultRepository resolutionResultRepository,
            CompositeEndpointResolver endpointResolver,
            AtsDetector atsDetector,
            DiscoveryProperties properties
    ) {
        this.providers = providers;
        this.companyNormalizer = companyNormalizer;
        this.companyRepository = companyRepository;
        this.careerEndpointRepository = careerEndpointRepository;
        this.discoveryEventRepository = discoveryEventRepository;
        this.resolutionResultRepository = resolutionResultRepository;
        this.endpointResolver = endpointResolver;
        this.atsDetector = atsDetector;
        this.properties = properties;
    }

    /**
     * Run full discovery cycle across all healthy providers.
     * Returns summary counts: [discovered, registered, alreadyExists, failed]
     */
    @Transactional
    public int[] runDiscovery() {
        log.info("Starting discovery run with {} providers", providers.size());

        DiscoveryQuery query = buildQuery();
        List<DiscoveredCompany> allDiscovered = new ArrayList<>();

        // Collect from all healthy providers
        for (DiscoveryProvider provider : providers) {
            if (!provider.isHealthy()) {
                log.debug("Skipping unhealthy provider: {}", provider.name());
                continue;
            }

            try {
                List<DiscoveredCompany> discovered = provider.discover(query);
                log.info("Provider '{}' discovered {} companies", provider.name(), discovered.size());
                allDiscovered.addAll(discovered);
            } catch (Exception e) {
                log.error("Provider '{}' failed: {}", provider.name(), e.getMessage());
            }
        }

        // Deduplicate by normalized name
        Map<String, DiscoveredCompany> deduplicated = new LinkedHashMap<>();
        for (DiscoveredCompany dc : allDiscovered) {
            String normalized = companyNormalizer.normalize(dc.companyName());
            if (!normalized.isBlank()) {
                deduplicated.putIfAbsent(normalized, dc);
            }
        }

        log.info("Deduplicated {} raw discoveries to {} unique companies",
                allDiscovered.size(), deduplicated.size());

        // Process each unique company
        int registered = 0;
        int alreadyExists = 0;
        int failed = 0;
        int newEndpoints = 0;

        for (Map.Entry<String, DiscoveredCompany> entry : deduplicated.entrySet()) {
            String normalizedName = entry.getKey();
            DiscoveredCompany discovered = entry.getValue();

            try {
                DiscoveryOutcome outcome = processDiscoveredCompany(normalizedName, discovered);
                switch (outcome) {
                    case REGISTERED -> registered++;
                    case ALREADY_EXISTS -> alreadyExists++;
                    case NEW_ENDPOINT_ADDED -> newEndpoints++;
                    case DETECTION_FAILED, UNSUPPORTED_ATS -> failed++;
                }
            } catch (Exception e) {
                log.error("Failed to process company '{}': {}", discovered.companyName(), e.getMessage());
                failed++;
                logDiscoveryEvent(null, discovered, DiscoveryOutcome.DETECTION_FAILED);
            }
        }

        log.info("Discovery complete: registered={}, alreadyExists={}, newEndpoints={}, failed={}",
                registered, alreadyExists, newEndpoints, failed);

        return new int[]{deduplicated.size(), registered, alreadyExists, failed};
    }

    private DiscoveryOutcome processDiscoveredCompany(String normalizedName, DiscoveredCompany discovered) {
        // Check if company already exists in registry
        Optional<Company> existing = companyRepository.findByNormalizedName(normalizedName);

        if (existing.isPresent()) {
            Company company = existing.get();
            // Check if we can add a new endpoint
            if (discovered.careerUrlHint() != null) {
                DiscoveryOutcome endpointOutcome = tryAddNewEndpoint(company, discovered);
                if (endpointOutcome == DiscoveryOutcome.NEW_ENDPOINT_ADDED) {
                    logDiscoveryEvent(company, discovered, DiscoveryOutcome.NEW_ENDPOINT_ADDED);
                    return DiscoveryOutcome.NEW_ENDPOINT_ADDED;
                }
            }
            logDiscoveryEvent(company, discovered, DiscoveryOutcome.ALREADY_EXISTS);
            return DiscoveryOutcome.ALREADY_EXISTS;
        }

        // New company - resolve career endpoint
        ResolutionResultDto resolution;
        if (discovered.careerUrlHint() != null && !discovered.careerUrlHint().isBlank()) {
            resolution = endpointResolver.resolveFromHint(discovered.companyName(), discovered.careerUrlHint());
        } else {
            resolution = endpointResolver.resolve(discovered.companyName(), null);
        }

        // Register company
        Company company = registerCompany(discovered, normalizedName, resolution);

        if (resolution.selectedUrl() != null && resolution.confidence() != Confidence.AMBIGUOUS) {
            createCareerEndpoint(company, resolution);
            persistResolutionResult(company, resolution);
            logDiscoveryEvent(company, discovered, DiscoveryOutcome.REGISTERED);
            return DiscoveryOutcome.REGISTERED;
        }

        // Could not resolve endpoint - still register company for later manual review
        if (resolution.needsManualReview()) {
            company.setStatus(CompanyStatus.PENDING_DETECTION);
            companyRepository.save(company);
            persistResolutionResult(company, resolution);
        }

        logDiscoveryEvent(company, discovered, DiscoveryOutcome.DETECTION_FAILED);
        return DiscoveryOutcome.DETECTION_FAILED;
    }

    private DiscoveryOutcome tryAddNewEndpoint(Company company, DiscoveredCompany discovered) {
        String hint = discovered.careerUrlHint();
        var detection = atsDetector.detectFromUrl(hint);

        if (detection.isEmpty()) {
            return DiscoveryOutcome.ALREADY_EXISTS;
        }

        // Check if this endpoint URL already exists for the company
        List<CareerEndpoint> existingEndpoints = careerEndpointRepository.findByCompanyId(company.getId());
        boolean urlExists = existingEndpoints.stream()
                .anyMatch(ep -> ep.getUrl().equalsIgnoreCase(hint));

        if (urlExists) {
            return DiscoveryOutcome.ALREADY_EXISTS;
        }

        // Add new endpoint
        AtsDetector.DetectionResult det = detection.get();
        CareerEndpoint endpoint = CareerEndpoint.builder()
                .company(company)
                .url(hint)
                .atsType(det.atsType())
                .atsSlug(det.slug())
                .confidence(det.confidence())
                .source("discovery:" + discovered.sourceUrl())
                .build();
        careerEndpointRepository.save(endpoint);

        return DiscoveryOutcome.NEW_ENDPOINT_ADDED;
    }

    private Company registerCompany(DiscoveredCompany discovered, String normalizedName, ResolutionResultDto resolution) {
        CompanyStatus status = resolution.selectedUrl() != null
                ? CompanyStatus.DISCOVERED
                : CompanyStatus.PENDING_DETECTION;

        DiscoverySource source = inferDiscoverySource(discovered.sourceUrl());

        Company company = Company.builder()
                .name(discovered.companyName())
                .normalizedName(normalizedName)
                .isActive(status == CompanyStatus.DISCOVERED)
                .status(status)
                .discoveredVia(source)
                .discoveredAt(LocalDateTime.now())
                .build();

        return companyRepository.save(company);
    }

    private void createCareerEndpoint(Company company, ResolutionResultDto resolution) {
        // Detect ATS from selected URL
        AtsType atsType = AtsType.UNKNOWN;
        String slug = null;
        Confidence confidence = resolution.confidence();

        var detection = atsDetector.detectFromUrl(resolution.selectedUrl());
        if (detection.isPresent()) {
            atsType = detection.get().atsType();
            slug = detection.get().slug();
            confidence = detection.get().confidence();
        }

        // Also check candidates for better detection
        if (atsType == AtsType.UNKNOWN && !resolution.candidateUrls().isEmpty()) {
            for (var candidate : resolution.candidateUrls()) {
                if (candidate.detectedAts() != AtsType.UNKNOWN) {
                    atsType = candidate.detectedAts();
                    confidence = candidate.confidence();
                    break;
                }
            }
        }

        CareerEndpoint endpoint = CareerEndpoint.builder()
                .company(company)
                .url(resolution.selectedUrl())
                .atsType(atsType)
                .atsSlug(slug)
                .confidence(confidence)
                .source("discovery:" + resolution.strategyUsed())
                .build();

        careerEndpointRepository.save(endpoint);

        // Update company status based on ATS support
        if (atsType == AtsType.UNKNOWN) {
            company.setStatus(CompanyStatus.PENDING_DETECTION);
        } else if (atsType == AtsType.WORKDAY_PROTECTED) {
            company.setStatus(CompanyStatus.PROTECTED);
        } else {
            company.setStatus(CompanyStatus.ACTIVE);
            company.setActive(true);
        }
        companyRepository.save(company);
    }

    private void persistResolutionResult(Company company, ResolutionResultDto resolution) {
        List<String> candidateUrlStrings = resolution.candidateUrls().stream()
                .map(ResolutionResultDto.CandidateUrl::url)
                .collect(Collectors.toList());

        dev.jobhub.model.ResolutionResult entity = dev.jobhub.model.ResolutionResult.builder()
                .company(company)
                .strategy(resolution.strategyUsed())
                .candidateUrls(candidateUrlStrings)
                .selectedUrl(resolution.selectedUrl())
                .confidence(resolution.confidence())
                .ambiguityReason(resolution.ambiguityReason())
                .resolvedAt(LocalDateTime.now())
                .needsManualReview(resolution.needsManualReview())
                .build();

        resolutionResultRepository.save(entity);
    }

    private void logDiscoveryEvent(Company company, DiscoveredCompany discovered, DiscoveryOutcome outcome) {
        DiscoveryEvent event = DiscoveryEvent.builder()
                .company(company)
                .companyName(discovered.companyName())
                .provider(inferProviderName(discovered.sourceUrl()))
                .sourceJobTitle(discovered.sourceJobTitle())
                .sourceUrl(discovered.sourceUrl())
                .outcome(outcome)
                .build();

        discoveryEventRepository.save(event);
    }

    private DiscoveryQuery buildQuery() {
        // Collect keywords and locations from all provider configs
        Set<String> keywords = new LinkedHashSet<>();
        Set<String> locations = new LinkedHashSet<>();

        for (var entry : properties.providers().entrySet()) {
            var config = entry.getValue();
            if (config.enabled()) {
                keywords.addAll(config.keywords());
                locations.addAll(config.locations());
            }
        }

        return new DiscoveryQuery(
                new ArrayList<>(keywords),
                new ArrayList<>(locations),
                LocalDate.now().minusDays(1)
        );
    }

    private DiscoverySource inferDiscoverySource(String sourceUrl) {
        if (sourceUrl == null) return DiscoverySource.MANUAL;
        String lower = sourceUrl.toLowerCase();
        if (lower.contains("stepstone")) return DiscoverySource.STEPSTONE;
        if (lower.contains("linkedin")) return DiscoverySource.LINKEDIN_ALERT;
        return DiscoverySource.JOBSPY;
    }

    private String inferProviderName(String sourceUrl) {
        if (sourceUrl == null) return "unknown";
        String lower = sourceUrl.toLowerCase();
        if (lower.contains("stepstone")) return "stepstone";
        if (lower.contains("linkedin")) return "linkedin-alerts";
        return "jobspy";
    }
}
