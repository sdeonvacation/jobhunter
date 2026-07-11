package dev.jobhunter.ingestion;

import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.Confidence;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.CareerEndpointRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.resolution.AtsDetector;
import dev.jobhunter.resolution.AtsDetector.DetectionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Auto-discovers career endpoints from aggregator job apply URLs.
 * After aggregator ingestion, scans today's KEEP jobs for ATS URLs and
 * creates CareerEndpoint records for newly-discovered company boards.
 */
@Slf4j
@Order(10)
@Component
public class AggregatorEndpointDiscoverer implements PostIngestionEnricher {

    private static final Set<AtsType> AGGREGATOR_ATS_TYPES = Set.of(
            AtsType.STEPSTONE, AtsType.INDEED, AtsType.LINKEDIN, AtsType.ARBEITNOW, AtsType.JOBGETHER
    );

    private final boolean enabled;
    private final AtsDetector atsDetector;
    private final JobPostingRepository jobPostingRepository;
    private final CareerEndpointRepository careerEndpointRepository;

    public AggregatorEndpointDiscoverer(
            @Value("${discovery.auto-endpoint.enabled:true}") boolean enabled,
            AtsDetector atsDetector,
            JobPostingRepository jobPostingRepository,
            CareerEndpointRepository careerEndpointRepository) {
        this.enabled = enabled;
        this.atsDetector = atsDetector;
        this.jobPostingRepository = jobPostingRepository;
        this.careerEndpointRepository = careerEndpointRepository;
    }

    @Override
    public void enrich(JobSource source, int created) {
        if (!enabled || created == 0) {
            return;
        }
        if (!source.isAggregator()) {
            return;
        }
        discoverEndpoints(source);
    }

    void discoverEndpoints(JobSource source) {
        List<JobPostingRepository.ApplyUrlProjection> jobs =
                jobPostingRepository.findApplyUrlsBySourceAndDate(source.name(), LocalDate.now());

        if (jobs.isEmpty()) {
            return;
        }

        // Detect ATS from apply URLs, keyed by (companyId, atsType, slug) for dedup
        Map<EndpointKey, CandidateEndpoint> candidates = new LinkedHashMap<>();
        for (JobPostingRepository.ApplyUrlProjection job : jobs) {
            Optional<DetectionResult> detection = atsDetector.detectFromUrl(job.getApplyUrl());
            if (detection.isEmpty()) {
                continue;
            }
            DetectionResult result = detection.get();
            if (AGGREGATOR_ATS_TYPES.contains(result.atsType())) {
                continue;
            }
            if (result.slug() == null) {
                continue;
            }
            String boardUrl = buildBoardUrl(result.atsType(), result.slug());
            if (boardUrl == null) {
                continue;
            }
            EndpointKey key = new EndpointKey(job.getCompanyId(), result.atsType(), result.slug());
            candidates.putIfAbsent(key, new CandidateEndpoint(boardUrl, result.confidence()));
        }

        if (candidates.isEmpty()) {
            return;
        }

        // Batch-load existing endpoints and remove already-known ones
        List<UUID> companyIds = candidates.keySet().stream()
                .map(EndpointKey::companyId)
                .distinct()
                .collect(Collectors.toList());

        Set<EndpointKey> existingKeys = careerEndpointRepository.findEndpointKeysByCompanyIds(companyIds).stream()
                .filter(p -> p.getAtsSlug() != null)
                .map(p -> new EndpointKey(
                        p.getCompanyId(),
                        AtsType.valueOf(p.getAtsType()),
                        p.getAtsSlug()))
                .collect(Collectors.toSet());

        candidates.keySet().removeAll(existingKeys);

        if (candidates.isEmpty()) {
            return;
        }

        // Create new endpoints
        List<CareerEndpoint> newEndpoints = new ArrayList<>();
        for (Map.Entry<EndpointKey, CandidateEndpoint> entry : candidates.entrySet()) {
            EndpointKey key = entry.getKey();
            CandidateEndpoint candidate = entry.getValue();

            CareerEndpoint endpoint = CareerEndpoint.builder()
                    .company(Company.builder().id(key.companyId()).build())
                    .url(candidate.boardUrl())
                    .atsType(key.atsType())
                    .atsSlug(key.slug())
                    .confidence(candidate.confidence())
                    .isActive(candidate.confidence() == Confidence.HIGH)
                    .source("aggregator-discovery:" + source.name().toLowerCase())
                    .verified(false)
                    .build();
            newEndpoints.add(endpoint);
        }

        careerEndpointRepository.saveAll(newEndpoints);
        log.info("Discovered {} new career endpoints from {} apply URLs (source={})",
                newEndpoints.size(), jobs.size(), source);
    }

    static String buildBoardUrl(AtsType atsType, String slug) {
        return switch (atsType) {
            case GREENHOUSE -> "https://boards.greenhouse.io/" + slug;
            case LEVER -> "https://jobs.lever.co/" + slug;
            case LEVER_EU -> "https://jobs.eu.lever.co/" + slug;
            case ASHBY -> "https://jobs.ashbyhq.com/" + slug;
            case PINPOINT -> "https://" + slug + ".pinpointhq.com";
            default -> null;
        };
    }

    private record EndpointKey(UUID companyId, AtsType atsType, String slug) {}
    private record CandidateEndpoint(String boardUrl, Confidence confidence) {}
}
