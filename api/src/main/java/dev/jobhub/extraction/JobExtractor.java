package dev.jobhub.extraction;

import dev.jobhub.model.CareerEndpoint;
import dev.jobhub.model.enums.AtsType;

import java.util.Set;

public interface JobExtractor {

    Set<AtsType> supportedTypes();

    ExtractionResult extract(CareerEndpoint endpoint);

    boolean canExtract(CareerEndpoint endpoint);
}
