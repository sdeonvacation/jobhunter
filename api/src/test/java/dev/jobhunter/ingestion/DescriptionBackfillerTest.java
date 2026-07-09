package dev.jobhunter.ingestion;

import dev.jobhunter.model.JobPosting;
import dev.jobhunter.service.MatchScoringService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

/**
 * Tests the template method in DescriptionBackfiller:
 * doBackfill() is called, then results are rescored.
 */
@ExtendWith(MockitoExtension.class)
class DescriptionBackfillerTest {

    @Mock
    private MatchScoringService matchScoringService;

    /** Minimal concrete subclass for testing the template method. */
    private static class StubBackfiller extends DescriptionBackfiller {
        private final List<JobPosting> toReturn;

        StubBackfiller(MatchScoringService matchScoringService, List<JobPosting> toReturn) {
            super(matchScoringService);
            this.toReturn = toReturn;
        }

        @Override
        protected List<JobPosting> doBackfill() {
            return toReturn;
        }
    }

    @Test
    void backfill_emptyResult_doesNotRescore() {
        var backfiller = new StubBackfiller(matchScoringService, List.of());

        backfiller.backfill();

        verifyNoInteractions(matchScoringService);
    }

    @Test
    void backfill_oneJob_rescoresIt() {
        var job = JobPosting.builder().id(UUID.randomUUID()).build();
        var backfiller = new StubBackfiller(matchScoringService, List.of(job));

        backfiller.backfill();

        verify(matchScoringService).rescoreJob(job);
        verifyNoMoreInteractions(matchScoringService);
    }

    @Test
    void backfill_multipleJobs_rescoresAll() {
        var job1 = JobPosting.builder().id(UUID.randomUUID()).build();
        var job2 = JobPosting.builder().id(UUID.randomUUID()).build();
        var job3 = JobPosting.builder().id(UUID.randomUUID()).build();
        var backfiller = new StubBackfiller(matchScoringService, List.of(job1, job2, job3));

        backfiller.backfill();

        verify(matchScoringService).rescoreJob(job1);
        verify(matchScoringService).rescoreJob(job2);
        verify(matchScoringService).rescoreJob(job3);
        verifyNoMoreInteractions(matchScoringService);
    }
}
