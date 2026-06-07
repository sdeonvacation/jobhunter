package dev.jobhunter.discovery;

import java.time.LocalDate;
import java.util.List;

public record DiscoveryQuery(
        List<String> keywords,
        List<String> locations,
        LocalDate since
) {}
