package dev.jobhunter.filter.visa;

/**
 * Strategy for detecting visa sponsorship signals in job descriptions.
 */
public interface VisaDetectionStrategy {

    VisaDetectionResult detect(String description);
}
