package dev.jobhub.extraction;

import dev.jobhub.model.enums.AtsType;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class JobExtractorRegistry {

    private final Map<AtsType, JobExtractor> extractors;

    public JobExtractorRegistry(List<JobExtractor> extractorList) {
        this.extractors = extractorList.stream()
                .flatMap(e -> e.supportedTypes().stream().map(type -> Map.entry(type, e)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Optional<JobExtractor> getExtractor(AtsType type) {
        return Optional.ofNullable(extractors.get(type));
    }

    public Set<AtsType> supportedTypes() {
        return Collections.unmodifiableSet(extractors.keySet());
    }
}
