package dev.jobhunter.strategy;

import dev.jobhunter.model.enums.AtsType;

public interface FetchStrategy {

    FetchResult fetch(FetchContext context);

    boolean supports(AtsType type);

    String name();
}
