package dev.jobhub.resolution;

import dev.jobhub.model.enums.AtsType;
import dev.jobhub.model.enums.Confidence;

import java.util.List;

public record ResolutionResultDto(
        List<CandidateUrl> candidateUrls,
        String selectedUrl,
        Confidence confidence,
        String strategyUsed,
        String ambiguityReason,
        boolean needsManualReview
) {

    public record CandidateUrl(
            String url,
            AtsType detectedAts,
            Confidence confidence,
            String discoveredVia
    ) {}
}
