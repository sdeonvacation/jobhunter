package dev.jobhunter.service;

import dev.jobhunter.repository.MatchScoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ScoringPostProcessorTest {

    @Mock private ScoringService scoringService;
    @Mock private MatchScoreRepository matchScoreRepository;

    @Test
    void process_delegatesToScoringServiceScoreAllUnscored() {
        var processor = new ScoringPostProcessor(scoringService, matchScoreRepository);

        processor.process();

        verify(scoringService).scoreAllUnscored();
    }

    @Test
    void process_scoringThrows_exceptionPropagates() {
        var processor = new ScoringPostProcessor(scoringService, matchScoreRepository);
        doThrow(new RuntimeException("scoring failed")).when(scoringService).scoreAllUnscored();

        org.assertj.core.api.Assertions.assertThatThrownBy(processor::process)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("scoring failed");
    }
}
