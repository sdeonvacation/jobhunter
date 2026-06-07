package dev.jobhunter.strategy;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RawAggregatorJob(
    String externalId,
    String title,
    String companyName,
    String location,
    String description,
    String applyUrl,
    LocalDate postedDate,
    BigDecimal salaryMin,
    BigDecimal salaryMax,
    String salaryCurrency,
    String rawJson
) {}
