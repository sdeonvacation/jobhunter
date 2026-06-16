package dev.jobhunter.people.poster;

import dev.jobhunter.model.enums.AtsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PosterExtractorRegistryTest {

    private PosterExtractorRegistry registry;

    @BeforeEach
    void setUp() {
        List<PosterExtractor> extractors = List.of(
                new GreenhousePosterExtractor(),
                new LeverPosterExtractor(),
                new AshbyPosterExtractor(),
                new SmartRecruitersPosterExtractor(),
                new TeamtailorPosterExtractor()
        );
        registry = new PosterExtractorRegistry(extractors);
    }

    @Test
    void getExtractor_returnsCorrectExtractorForGreenhouse() {
        Optional<PosterExtractor> extractor = registry.getExtractor(AtsType.GREENHOUSE);
        assertThat(extractor).isPresent();
        assertThat(extractor.get()).isInstanceOf(GreenhousePosterExtractor.class);
    }

    @Test
    void getExtractor_returnsCorrectExtractorForLever() {
        Optional<PosterExtractor> extractor = registry.getExtractor(AtsType.LEVER);
        assertThat(extractor).isPresent();
        assertThat(extractor.get()).isInstanceOf(LeverPosterExtractor.class);
    }

    @Test
    void getExtractor_returnsCorrectExtractorForLeverEu() {
        Optional<PosterExtractor> extractor = registry.getExtractor(AtsType.LEVER_EU);
        assertThat(extractor).isPresent();
        assertThat(extractor.get()).isInstanceOf(LeverPosterExtractor.class);
    }

    @Test
    void getExtractor_returnsCorrectExtractorForAshby() {
        Optional<PosterExtractor> extractor = registry.getExtractor(AtsType.ASHBY);
        assertThat(extractor).isPresent();
        assertThat(extractor.get()).isInstanceOf(AshbyPosterExtractor.class);
    }

    @Test
    void getExtractor_returnsCorrectExtractorForSmartRecruiters() {
        Optional<PosterExtractor> extractor = registry.getExtractor(AtsType.SMARTRECRUITERS);
        assertThat(extractor).isPresent();
        assertThat(extractor.get()).isInstanceOf(SmartRecruitersPosterExtractor.class);
    }

    @Test
    void getExtractor_returnsCorrectExtractorForTeamtailor() {
        Optional<PosterExtractor> extractor = registry.getExtractor(AtsType.TEAMTAILOR);
        assertThat(extractor).isPresent();
        assertThat(extractor.get()).isInstanceOf(TeamtailorPosterExtractor.class);
    }

    @Test
    void getExtractor_returnsEmptyForUnsupportedType() {
        Optional<PosterExtractor> extractor = registry.getExtractor(AtsType.WORKDAY);
        assertThat(extractor).isEmpty();
    }

    @Test
    void getSupportedTypes_containsAllRegisteredTypes() {
        Set<AtsType> supported = registry.getSupportedTypes();
        assertThat(supported).contains(
                AtsType.GREENHOUSE, AtsType.LEVER, AtsType.LEVER_EU,
                AtsType.ASHBY, AtsType.SMARTRECRUITERS, AtsType.TEAMTAILOR
        );
    }

    @Test
    void constructor_handlesEmptyList() {
        PosterExtractorRegistry emptyRegistry = new PosterExtractorRegistry(List.of());
        assertThat(emptyRegistry.getSupportedTypes()).isEmpty();
        assertThat(emptyRegistry.getExtractor(AtsType.GREENHOUSE)).isEmpty();
    }
}
