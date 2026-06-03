package dev.jobhub.dto;

import java.util.List;

public record TechStackDto(
        List<String> languages,
        List<String> frameworks,
        List<String> databases,
        List<String> cloud,
        List<String> tools,
        List<String> methodologies
) {}
