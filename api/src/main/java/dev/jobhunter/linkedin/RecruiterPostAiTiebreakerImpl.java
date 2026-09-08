package dev.jobhunter.linkedin;

import dev.jobhunter.ai.AiProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LLM tiebreaker that determines whether a candidate LinkedIn post refers to
 * the specific job opening under consideration.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class RecruiterPostAiTiebreakerImpl implements RecruiterPostAiTiebreaker {

    private static final String SYSTEM_PROMPT =
            "You are a job-matching assistant. Determine whether a LinkedIn post refers to a specific job opening. "
                    + "Answer with JSON: {\"refersToThisOpening\": true|false, \"reason\": \"...\"}";

    private final AiProvider aiProvider;
    private final boolean aiVerificationEnabled;

    public RecruiterPostAiTiebreakerImpl(AiProvider aiProvider,
                                         @Value("${linkedin-mcp.recruiter-post-check.ai-verification-enabled:true}")
                                         boolean aiVerificationEnabled) {
        this.aiProvider = aiProvider;
        this.aiVerificationEnabled = aiVerificationEnabled;
    }

    @Override
    public AiVerdict classify(SignalScorer.CandidatePost post, JobContextResolver.JobContext ctx) {
        if (!aiVerificationEnabled || !aiProvider.isAvailable()) {
            return AiVerdict.UNKNOWN;
        }
        if (post == null || ctx == null) {
            return AiVerdict.UNKNOWN;
        }

        String content = "Job: " + ctx.title() + " at " + ctx.company()
                + "\nPost by " + post.authorName() + " (" + post.authorTitle() + "):\n"
                + post.snippet();

        try {
            AiClassification classification = aiProvider.extract(SYSTEM_PROMPT, content, AiClassification.class);
            if (classification == null) {
                return AiVerdict.UNKNOWN;
            }
            return classification.refersToThisOpening() ? AiVerdict.YES : AiVerdict.NO;
        } catch (Exception e) {
            log.warn("AI tiebreaker classification failed for post by {}: {}", post.authorName(), e.getMessage());
            return AiVerdict.UNKNOWN;
        }
    }

    public record AiClassification(boolean refersToThisOpening, String reason) {}
}