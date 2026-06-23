package dev.jobhunter.strategy;

import dev.jobhunter.model.enums.AtsType;
import java.util.Set;

public interface FetchStrategy {

    FetchResult fetch(FetchContext context);

    default Set<AtsType> supportedTypes() {
        return Set.of();
    }

    /** @deprecated Use {@link #supportedTypes()} instead. */
    @Deprecated
    default boolean supports(AtsType type) {
        return false;
    }

    String name();
}
