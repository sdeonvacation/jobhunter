package dev.jobhunter.filter.visa;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI-powered visa sponsorship detection as fallback when regex is inconclusive.
 * Gated by config flag and daily usage limit.
 */
@Slf4j
@Component
public class AiVisaDetectionStrategy implements VisaDetectionStrategy {

    private static final String SYSTEM_PROMPT =
            "You are analyzing a job description to determine if the company offers visa/work permit sponsorship " +
            "for international candidates. Respond with exactly one word: yes, no, or unclear.";

    private final AiProvider aiProvider;
    private final boolean enabled;
    private final int maxDescriptionChars;
    private final int dailyLimit;

    private final AtomicInteger dailyUsageCount = new AtomicInteger(0);
    private final AtomicReference<LocalDate> lastResetDate = new AtomicReference<>(LocalDate.now());

    public AiVisaDetectionStrategy(AiProvider aiProvider, PersonalProfileLoader profileLoader) {
        this.aiProvider = aiProvider;

        PersonalProfile profile = profileLoader.getProfile();
        PersonalProfile.FilterConfig filters = profile.filters();
        PersonalProfile.VisaSponsorshipFilterConfig visaConfig =
                (filters != null) ? filters.visaSponsorship() : null;
        PersonalProfile.AiFallbackConfig aiFallback =
                (visaConfig != null) ? visaConfig.aiFallback() : null;

        if (aiFallback != null) {
            this.enabled = aiFallback.enabled();
            this.maxDescriptionChars = aiFallback.maxDescriptionChars();
            this.dailyLimit = aiFallback.dailyLimit();
        } else {
            this.enabled = false;
            this.maxDescriptionChars = 4000;
            this.dailyLimit = 50;
        }

        log.info("AiVisaDetectionStrategy initialized: enabled={}, maxChars={}, dailyLimit={}",
                enabled, maxDescriptionChars, dailyLimit);
    }

    @Override
    public VisaDetectionResult detect(String description) {
        if (!enabled || !aiProvider.isAvailable()) {
            return VisaDetectionResult.unknown("AI disabled");
        }

        resetDailyCounterIfNeeded();

        if (dailyUsageCount.get() >= dailyLimit) {
            return VisaDetectionResult.unknown("daily limit reached");
        }

        try {
            String truncated = truncate(description);
            dailyUsageCount.incrementAndGet();

            String response = aiProvider.generate(SYSTEM_PROMPT, truncated);
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("AI visa detection failed: {}", e.getMessage());
            return VisaDetectionResult.unknown("AI error: " + e.getMessage());
        }
    }

    private VisaDetectionResult parseResponse(String response) {
        if (response == null || response.isBlank()) {
            return VisaDetectionResult.unknown("AI returned empty response");
        }

        String normalized = response.strip().toLowerCase();
        return switch (normalized) {
            case "yes" -> new VisaDetectionResult(VisaSponsorship.CONFIRMED, 0.7, "AI detected sponsorship");
            case "no" -> new VisaDetectionResult(VisaSponsorship.REJECTED, 0.7, "AI detected no sponsorship");
            default -> VisaDetectionResult.unknown("AI response inconclusive: " + normalized);
        };
    }

    private String truncate(String description) {
        if (description == null) return "";
        return description.length() > maxDescriptionChars
                ? description.substring(0, maxDescriptionChars)
                : description;
    }

    private void resetDailyCounterIfNeeded() {
        LocalDate today = LocalDate.now();
        LocalDate lastReset = lastResetDate.get();
        if (!today.equals(lastReset)) {
            if (lastResetDate.compareAndSet(lastReset, today)) {
                dailyUsageCount.set(0);
                log.debug("AI visa detection daily counter reset");
            }
        }
    }
}
