package dev.jobhunter.resolution;

import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.Confidence;

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
