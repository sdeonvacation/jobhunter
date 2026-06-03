package dev.jobhub.resolution;

import dev.jobhub.model.enums.AtsType;
import dev.jobhub.model.enums.Confidence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves career endpoint via Google Custom Search API.
 * MEDIUM confidence results. Respects quota limits.
 */
@Slf4j
@Component
public class GoogleSearchResolver implements EndpointResolver {

    private final WebClient webClient;
    private final AtsDetector atsDetector;
    private final String apiKey;
    private final String cx;

    public GoogleSearchResolver(
            WebClient webClient,
            AtsDetector atsDetector,
            @Value("${resolution.google-search.api-key:}") String apiKey,
            @Value("${resolution.google-search.cx:}") String cx
    ) {
        this.webClient = webClient;
        this.atsDetector = atsDetector;
        this.apiKey = apiKey;
        this.cx = cx;
    }

    @Override
    public ResolutionResultDto resolve(String companyName, String domain) {
        if (!isConfigured()) {
            log.debug("Google Search resolver not configured, skipping");
            return empty();
        }

        String query = buildQuery(companyName, domain);
        log.debug("Google Search query: {}", query);

        try {
            var response = webClient.get()
                    .uri("https://www.googleapis.com/customsearch/v1", uriBuilder ->
                            uriBuilder
                                    .queryParam("key", apiKey)
                                    .queryParam("cx", cx)
                                    .queryParam("q", query)
                                    .queryParam("num", 5)
                                    .build()
                    )
                    .retrieve()
                    .bodyToMono(GoogleSearchResponse.class)
                    .block();

            if (response == null || response.items() == null || response.items().isEmpty()) {
                return empty();
            }

            List<ResolutionResultDto.CandidateUrl> candidates = new ArrayList<>();
            String selectedUrl = null;
            Confidence bestConfidence = Confidence.LOW;

            for (GoogleSearchResponse.Item item : response.items()) {
                String url = item.link();
                var detection = atsDetector.detectFromUrl(url);

                if (detection.isPresent()) {
                    AtsType atsType = detection.get().atsType();
                    Confidence confidence = Confidence.MEDIUM;
                    candidates.add(new ResolutionResultDto.CandidateUrl(
                            url, atsType, confidence, "GOOGLE_SEARCH"
                    ));
                    if (selectedUrl == null) {
                        selectedUrl = url;
                        bestConfidence = confidence;
                    }
                }
            }

            if (candidates.isEmpty()) {
                return empty();
            }

            return new ResolutionResultDto(
                    candidates,
                    selectedUrl,
                    bestConfidence,
                    "GOOGLE_SEARCH",
                    null,
                    false
            );
        } catch (Exception e) {
            log.warn("Google Search resolution failed for '{}': {}", companyName, e.getMessage());
            return empty();
        }
    }

    private boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && cx != null && !cx.isBlank();
    }

    private String buildQuery(String companyName, String domain) {
        if (domain != null && !domain.isBlank()) {
            return companyName + " careers site:" + domain;
        }
        return companyName + " careers jobs apply";
    }

    private ResolutionResultDto empty() {
        return new ResolutionResultDto(List.of(), null, Confidence.LOW, "GOOGLE_SEARCH", null, false);
    }

    // Internal DTO for Google API response
    record GoogleSearchResponse(List<Item> items) {
        record Item(String title, String link, String snippet) {}
    }
}
