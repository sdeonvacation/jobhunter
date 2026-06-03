package dev.jobhub.dto;

import java.util.List;

public record TailorResumeRequest(
        String emphasis,
        List<String> excludeSkills
) {}
