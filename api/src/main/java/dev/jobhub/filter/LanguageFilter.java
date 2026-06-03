package dev.jobhub.filter;

public interface LanguageFilter {

    FilterResult filter(String jobTitle, String jobDescription);
}
