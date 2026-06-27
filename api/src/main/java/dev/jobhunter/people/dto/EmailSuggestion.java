package dev.jobhunter.people.dto;

import dev.jobhunter.people.model.enums.EmailConfidence;

public record EmailSuggestion(String email, String pattern, EmailConfidence confidence) {}
