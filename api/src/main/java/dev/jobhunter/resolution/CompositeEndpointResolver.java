package dev.jobhunter.resolution;

import dev.jobhunter.model.enums.Confidence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Composite resolver that tries strategies in configured order.
 * Stops on first HIGH confidence result. Collects all candidates from all strategies tried.
 */
@Slf4j
@Component
public class CompositeEndpointResolver implements EndpointResolver {

    private final Map<String, EndpointResolver> resolvers;
    private final PatternMatchResolver patternMatchResolver;
    private final List<String> strategyOrder;

    public CompositeEndpointResolver(
            PatternMatchResolver patternMatchResolver,
            GoogleSearchResolver googleSearchResolver,
            RedirectFollowResolver redirectFollowResolver,
            @Value("${resolution.strategies-order:PATTERN_MATCH,GOOGLE_SEARCH,REDIRECT_FOLLOW}") List<String> strategyOrder
    ) {
        this.patternMatchResolver = patternMatchResolver;
        this.strategyOrder = strategyOrder;
        this.resolvers = Map.of(
                "PATTERN_MATCH", patternMatchResolver,
                "GOOGLE_SEARCH", googleSearchResolver,
                "REDIRECT_FOLLOW", redirectFollowResolver
        );
    }

    @Override
    public ResolutionResultDto resolve(String companyName, String domain) {
        List<ResolutionResultDto.CandidateUrl> allCandidates = new ArrayList<>();
        String selectedUrl = null;
        Confidence bestConfidence = Confidence.LOW;
        String strategyUsed = null;

        for (String strategy : strategyOrder) {
            EndpointResolver resolver = resolvers.get(strategy);
            if (resolver == null) {
                log.warn("Unknown resolution strategy: {}", strategy);
                continue;
            }

            log.debug("Trying resolution strategy: {} for company '{}'", strategy, companyName);

            try {
                ResolutionResultDto result = resolver.resolve(companyName, domain);
                allCandidates.addAll(result.candidateUrls());

                if (result.selectedUrl() != null && result.confidence().ordinal() < bestConfidence.ordinal()) {
                    selectedUrl = result.selectedUrl();
                    bestConfidence = result.confidence();
                    strategyUsed = strategy;
                }

                // Stop on HIGH confidence
                if (result.confidence() == Confidence.HIGH) {
                    log.info("HIGH confidence resolution for '{}' via {}: {}",
                            companyName, strategy, result.selectedUrl());
                    break;
                }
            } catch (Exception e) {
                log.warn("Resolution strategy {} failed for '{}': {}", strategy, companyName, e.getMessage());
            }
        }

        boolean needsManualReview = selectedUrl == null || bestConfidence == Confidence.AMBIGUOUS;
        String ambiguityReason = null;
        if (allCandidates.size() > 1 && selectedUrl == null) {
            ambiguityReason = "Multiple candidates found but none with high confidence";
            needsManualReview = true;
        }

        return new ResolutionResultDto(
                allCandidates,
                selectedUrl,
                bestConfidence,
                strategyUsed != null ? strategyUsed : "NONE",
                ambiguityReason,
                needsManualReview
        );
    }

    /**
     * Resolve from a known URL hint (e.g. from discovery provider).
     * Tries pattern matching first on the hint URL directly.
     */
    public ResolutionResultDto resolveFromHint(String companyName, String careerUrlHint) {
        // First try direct pattern match on the hint
        ResolutionResultDto hintResult = patternMatchResolver.resolveFromUrl(careerUrlHint);
        if (hintResult.confidence() == Confidence.HIGH) {
            return hintResult;
        }

        // Fall back to full resolution
        String domain = extractDomain(careerUrlHint);
        return resolve(companyName, domain);
    }

    private String extractDomain(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host != null && host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host;
        } catch (Exception e) {
            return null;
        }
    }
}
