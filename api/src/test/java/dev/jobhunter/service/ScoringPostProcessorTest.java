package dev.jobhunter.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ScoringPostProcessorTest {

    @Mock private ScoringService scoringService;

    @Test
    void process_delegatesToScoringServiceScoreAllUnscored() {
        var processor = new ScoringPostProcessor(scoringService);

        processor.process();

        verify(scoringService).scoreAllUnscored();
    }

    @Test
    void process_scoringThrows_exceptionPropagates() {
        var processor = new ScoringPostProcessor(scoringService);
        doThrow(new RuntimeException("scoring failed")).when(scoringService).scoreAllUnscored();

        org.assertj.core.api.Assertions.assertThatThrownBy(processor::process)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("scoring failed");
    }
}
