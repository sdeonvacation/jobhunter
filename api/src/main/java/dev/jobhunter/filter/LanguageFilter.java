package dev.jobhunter.filter;

public interface LanguageFilter {

    FilterResult filter(String jobTitle, String jobDescription);
}
