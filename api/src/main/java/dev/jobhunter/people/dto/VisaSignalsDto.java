package dev.jobhunter.people.dto;

public record VisaSignalsDto(
        Boolean hasSponsoredBefore,
        Boolean englishSpeaking,
        Boolean internationalWorkforce,
        String derived
) {}
