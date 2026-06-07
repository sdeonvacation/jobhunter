package dev.jobhunter.strategy.aggregator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import dev.jobhunter.strategy.RawAggregatorJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
public class AiAggregatorStrategy implements FetchStrategy {

    private static final String EXTRACTION_PROMPT = """
            Extract job listings from this HTML page. For each job return a JSON array of objects with these fields:
            - title: job title
            - companyName: company name
            - location: job location (or "Berlin" if not specified)
            - description: brief description snippet (max 200 chars)
            - applyUrl: the apply/detail URL (absolute URL)
            
            Return ONLY a valid JSON array, no markdown or explanation. If no jobs found, return [].
            """;

    private final WebClient webClient;
    private final AiProvider aiProvider;
    private final ObjectMapper objectMapper;

    public AiAggregatorStrategy(WebClient webClient, AiProvider aiProvider) {
        this.webClient = webClient;
        this.aiProvider = aiProvider;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String name() {
        return "ai";
    }

    @Override
    public boolean supports(AtsType type) {
        return false;
    }

    @Override
    public FetchResult fetch(FetchContext context) {
        Instant start = Instant.now();
        String url = (String) context.config().get("url");

        if (url == null || url.isBlank()) {
            return FetchResult.error("No URL configured in context", elapsed(start));
        }

        if (!aiProvider.isAvailable()) {
            return FetchResult.error("AI provider not available", elapsed(start));
        }

        try {
            log.info("Fetching HTML from {} for AI extraction", url);
            String html = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            if (html == null || html.isBlank()) {
                return FetchResult.empty(elapsed(start));
            }

            // Truncate HTML to avoid exceeding AI context limits
            String truncatedHtml = html.length() > 50_000 ? html.substring(0, 50_000) : html;

            log.debug("Sending {} chars of HTML to AI for extraction", truncatedHtml.length());
            String aiResponse = aiProvider.generate(EXTRACTION_PROMPT, truncatedHtml);

            List<AiExtractedJob> extracted = parseAiResponse(aiResponse);
            if (extracted.isEmpty()) {
                return FetchResult.empty(elapsed(start));
            }

            List<RawAggregatorJob> jobs = extracted.stream()
                    .limit(context.maxResults())
                    .map(this::toRawJob)
                    .toList();

            log.info("AI extracted {} jobs from {}", jobs.size(), url);
            return FetchResult.success(jobs, elapsed(start));

        } catch (Exception e) {
            log.error("AI aggregator fetch failed for {}: {}", url, e.getMessage());
            return FetchResult.error("AI extraction failed: " + e.getMessage(), elapsed(start));
        }
    }

    private List<AiExtractedJob> parseAiResponse(String response) {
        try {
            // Strip markdown code fences if present
            String json = response.strip();
            if (json.startsWith("```")) {
                json = json.replaceFirst("```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            }
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse AI response as JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private RawAggregatorJob toRawJob(AiExtractedJob extracted) {
        String syntheticId = generateExternalId(extracted);
        return new RawAggregatorJob(
                syntheticId,
                extracted.title(),
                extracted.companyName(),
                extracted.location(),
                extracted.description(),
                extracted.applyUrl(),
                LocalDate.now(),
                null, null, null, null
        );
    }

    private String generateExternalId(AiExtractedJob job) {
        String content = String.join("|",
                job.title() != null ? job.title() : "",
                job.companyName() != null ? job.companyName() : "",
                job.applyUrl() != null ? job.applyUrl() : "");
        return Integer.toHexString(content.hashCode());
    }

    private Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }

    record AiExtractedJob(
            String title,
            String companyName,
            String location,
            String description,
            String applyUrl
    ) {}
}
