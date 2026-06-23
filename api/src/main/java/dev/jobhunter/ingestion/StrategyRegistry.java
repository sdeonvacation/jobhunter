package dev.jobhunter.ingestion;

import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.strategy.FetchStrategy;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class StrategyRegistry {

    private final Map<AtsType, FetchStrategy> byType;
    private final Map<String, FetchStrategy> byName;

    public StrategyRegistry(List<FetchStrategy> strategies) {
        this.byName = strategies.stream()
            .collect(Collectors.toMap(FetchStrategy::name, s -> s));
        this.byType = new HashMap<>();
        for (FetchStrategy s : strategies) {
            for (AtsType t : s.supportedTypes()) {
                byType.put(t, s);
            }
        }
    }

    public Optional<FetchStrategy> getStrategy(AtsType type) {
        return Optional.ofNullable(byType.get(type));
    }

    public Optional<FetchStrategy> getStrategy(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Set<AtsType> supportedTypes() {
        return Collections.unmodifiableSet(byType.keySet());
    }
}
