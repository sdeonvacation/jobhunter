package dev.jobhub.extraction;

import java.util.List;

public record AiExtractionResponse(List<AiJobEntry> jobs) {

    public record AiJobEntry(
            String title,
            String location,
            String applyUrl
    ) {}
}
