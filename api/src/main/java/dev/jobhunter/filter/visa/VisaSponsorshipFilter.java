package dev.jobhunter.filter.visa;

public interface VisaSponsorshipFilter {
    VisaFilterResult filter(String location, String description, boolean isAggregator);
    String extractCountry(String location);
}
