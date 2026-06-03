package dev.jobhub.extraction;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RawJobData(
        String externalId,
        String title,
        String location,
        String description,
        String applyUrl,
        String rawJson,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String salaryCurrency,
        LocalDate postedDate
) {}
