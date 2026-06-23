package dev.jobhunter.filter;

public interface DeduplicationFilter {
    String generateFingerprint(String title, String companyName, String location);
}
