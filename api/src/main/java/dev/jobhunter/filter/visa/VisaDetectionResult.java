package dev.jobhunter.filter.visa;

import dev.jobhunter.model.enums.VisaSponsorship;

/**
 * Result of visa sponsorship detection with confidence score and reasoning.
 */
public record VisaDetectionResult(
        VisaSponsorship status,
        double confidence,
        String reason
) {

    public static VisaDetectionResult confirmed(double confidence, String reason) {
        return new VisaDetectionResult(VisaSponsorship.CONFIRMED, confidence, reason);
    }

    public static VisaDetectionResult rejected(double confidence, String reason) {
        return new VisaDetectionResult(VisaSponsorship.REJECTED, confidence, reason);
    }

    public static VisaDetectionResult unclear() {
        return new VisaDetectionResult(VisaSponsorship.PENDING, 0.0, "no definitive signal");
    }

    public static VisaDetectionResult unknown(String reason) {
        return new VisaDetectionResult(VisaSponsorship.UNKNOWN, 0.0, reason);
    }

    public boolean isDefinitive() {
        return status == VisaSponsorship.CONFIRMED || status == VisaSponsorship.REJECTED;
    }
}
