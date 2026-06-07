package dev.jobhunter.resolution;

import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.Confidence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves career endpoint by following HTTP redirects from common career page paths.
 * LOW confidence results. Checks final URL for ATS patterns.
 */
@Slf4j
@Component
public class RedirectFollowResolver implements EndpointResolver {

    private static final int MAX_REDIRECTS = 5;
    private static final List<String> PATH_TEMPLATES = List.of(
            "https://careers.%s",
            "https://%s/careers",
            "https://%s/jobs"
    );

    private final WebClient webClient;
    private final AtsDetector atsDetector;
    private final Duration timeout;

    public RedirectFollowResolver(
            WebClient webClient,
            AtsDetector atsDetector,
            @Value("${resolution.timeout-seconds:15}") int timeoutSeconds
    ) {
        this.webClient = webClient;
        this.atsDetector = atsDetector;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public ResolutionResultDto resolve(String companyName, String domain) {
        if (domain == null || domain.isBlank()) {
            return empty();
        }

        List<ResolutionResultDto.CandidateUrl> candidates = new ArrayList<>();

        for (String template : PATH_TEMPLATES) {
            String startUrl = String.format(template, domain);
            try {
                String finalUrl = followRedirects(startUrl);
                if (finalUrl != null) {
                    var detection = atsDetector.detectFromUrl(finalUrl);
                    AtsType atsType = detection.map(AtsDetector.DetectionResult::atsType).orElse(AtsType.UNKNOWN);
                    Confidence confidence = detection.isPresent() ? Confidence.LOW : Confidence.LOW;

                    candidates.add(new ResolutionResultDto.CandidateUrl(
                            finalUrl, atsType, confidence, "REDIRECT_FOLLOW"
                    ));
                }
            } catch (Exception e) {
                log.debug("Redirect follow failed for {}: {}", startUrl, e.getMessage());
            }
        }

        if (candidates.isEmpty()) {
            return empty();
        }

        // Select best candidate (prefer one with detected ATS)
        var best = candidates.stream()
                .filter(c -> c.detectedAts() != AtsType.UNKNOWN)
                .findFirst()
                .orElse(candidates.getFirst());

        return new ResolutionResultDto(
                candidates,
                best.url(),
                best.confidence(),
                "REDIRECT_FOLLOW",
                null,
                best.detectedAts() == AtsType.UNKNOWN
        );
    }

    private String followRedirects(String startUrl) {
        String currentUrl = startUrl;

        for (int i = 0; i < MAX_REDIRECTS; i++) {
            try {
                final String urlToFetch = currentUrl;
                var response = webClient.get()
                        .uri(urlToFetch)
                        .exchangeToMono(clientResponse -> {
                            int status = clientResponse.statusCode().value();
                            if (status >= 300 && status < 400) {
                                String location = clientResponse.headers()
                                        .header(HttpHeaders.LOCATION)
                                        .stream().findFirst().orElse(null);
                                return Mono.justOrEmpty(location);
                            }
                            if (status >= 200 && status < 300) {
                                // Successful response - current URL is final
                                return Mono.just(urlToFetch);
                            }
                            return Mono.empty();
                        })
                        .timeout(timeout)
                        .block();

                if (response == null) {
                    return null;
                }

                // If response equals currentUrl, we've reached the final destination
                if (response.equals(currentUrl)) {
                    return currentUrl;
                }

                // Resolve relative redirects
                if (!response.startsWith("http")) {
                    URI base = URI.create(currentUrl);
                    currentUrl = base.resolve(response).toString();
                } else {
                    currentUrl = response;
                }
            } catch (Exception e) {
                log.debug("HTTP request failed for {}: {}", currentUrl, e.getMessage());
                return null;
            }
        }

        return currentUrl;
    }

    private ResolutionResultDto empty() {
        return new ResolutionResultDto(List.of(), null, Confidence.LOW, "REDIRECT_FOLLOW", null, false);
    }
}
