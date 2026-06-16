package dev.jobhunter.people.poster;

import dev.jobhunter.model.enums.AtsType;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class PosterExtractorRegistry {

    private final Map<AtsType, PosterExtractor> extractors;

    public PosterExtractorRegistry(List<PosterExtractor> allExtractors) {
        this.extractors = allExtractors.stream()
                .flatMap(e -> Arrays.stream(AtsType.values())
                        .filter(e::supports)
                        .map(type -> Map.entry(type, e)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    public Optional<PosterExtractor> getExtractor(AtsType type) {
        return Optional.ofNullable(extractors.get(type));
    }

    public Set<AtsType> getSupportedTypes() {
        return Collections.unmodifiableSet(extractors.keySet());
    }
}
