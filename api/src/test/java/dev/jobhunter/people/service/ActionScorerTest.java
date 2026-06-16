package dev.jobhunter.people.service;

import dev.jobhunter.people.service.ActionScorer.ActionCandidate;
import dev.jobhunter.people.service.ActionScorer.ActionType;
import dev.jobhunter.people.service.ActionScorer.ScoredAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActionScorerTest {

    private ActionScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new ActionScorer();
    }

    @Test
    void score_overdueDeadline_urgencyIs1_2() {
        Instant lastActivity = Instant.now().minus(Duration.ofDays(10));
        Instant deadline = Instant.now().minus(Duration.ofDays(1)); // overdue

        ActionCandidate candidate = new ActionCandidate(
                UUID.randomUUID(), ActionType.FOLLOW_UP, UUID.randomUUID(), null,
                lastActivity, deadline, 0.8
        );

        ScoredAction result = scorer.score(candidate);

        assertThat(result.urgencyScore()).isEqualTo(1.2);
        assertThat(result.actionScore()).isEqualTo(0.8 * 1.2);
    }

    @Test
    void score_halfwayThroughWindow_urgencyIs0_5() {
        Instant lastActivity = Instant.now().minus(Duration.ofDays(10));
        Instant deadline = Instant.now().plus(Duration.ofDays(10)); // halfway

        ActionCandidate candidate = new ActionCandidate(
                UUID.randomUUID(), ActionType.CONNECT, UUID.randomUUID(), null,
                lastActivity, deadline, 0.6
        );

        ScoredAction result = scorer.score(candidate);

        // urgency = 1.0 - (10 / 20) = 0.5
        assertThat(result.urgencyScore()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void score_justStarted_urgencyIsNearZero() {
        Instant lastActivity = Instant.now();
        Instant deadline = Instant.now().plus(Duration.ofDays(14));

        ActionCandidate candidate = new ActionCandidate(
                UUID.randomUUID(), ActionType.APPLY, null, UUID.randomUUID(),
                lastActivity, deadline, 1.0
        );

        ScoredAction result = scorer.score(candidate);

        assertThat(result.urgencyScore()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void score_nearDeadline_urgencyIsHigh() {
        Instant lastActivity = Instant.now().minus(Duration.ofDays(13));
        Instant deadline = Instant.now().plus(Duration.ofDays(1)); // 1 day left of 14

        ActionCandidate candidate = new ActionCandidate(
                UUID.randomUUID(), ActionType.SEND_MESSAGE, UUID.randomUUID(), null,
                lastActivity, deadline, 0.7
        );

        ScoredAction result = scorer.score(candidate);

        // urgency = 1.0 - (1 / 14) ≈ 0.929
        assertThat(result.urgencyScore()).isGreaterThan(0.9);
        assertThat(result.urgencyScore()).isLessThanOrEqualTo(1.5);
    }

    @Test
    void score_nullDeadline_defaultsTo1_0() {
        ActionCandidate candidate = new ActionCandidate(
                UUID.randomUUID(), ActionType.PREPARE, null, UUID.randomUUID(),
                Instant.now(), null, 0.5
        );

        ScoredAction result = scorer.score(candidate);

        assertThat(result.urgencyScore()).isEqualTo(1.0);
        assertThat(result.actionScore()).isEqualTo(0.5);
    }

    @Test
    void score_nullLastActivity_defaultsTo1_0() {
        ActionCandidate candidate = new ActionCandidate(
                UUID.randomUUID(), ActionType.FOLLOW_UP, UUID.randomUUID(), null,
                null, Instant.now().plus(Duration.ofDays(3)), 0.9
        );

        ScoredAction result = scorer.score(candidate);

        assertThat(result.urgencyScore()).isEqualTo(1.0);
        assertThat(result.actionScore()).isEqualTo(0.9);
    }

    @Test
    void score_urgencyClampedAtMax1_5() {
        // Edge: zero-length window but not yet overdue (totalWindowMs <= 0)
        Instant now = Instant.now();
        ActionCandidate candidate = new ActionCandidate(
                UUID.randomUUID(), ActionType.FOLLOW_UP, UUID.randomUUID(), null,
                now, now, 1.0 // same instant = zero window
        );

        ScoredAction result = scorer.score(candidate);

        // Zero window treated as overdue
        assertThat(result.urgencyScore()).isEqualTo(1.2);
    }

    @Test
    void score_actionScoreMultipliesImpactAndUrgency() {
        Instant lastActivity = Instant.now().minus(Duration.ofDays(7));
        Instant deadline = Instant.now().minus(Duration.ofDays(1)); // overdue

        ActionCandidate candidate = new ActionCandidate(
                UUID.randomUUID(), ActionType.FOLLOW_UP, UUID.randomUUID(), null,
                lastActivity, deadline, 0.9
        );

        ScoredAction result = scorer.score(candidate);

        assertThat(result.actionScore()).isEqualTo(0.9 * 1.2);
    }

    @Test
    void score_preservesEntityFields() {
        UUID entityId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        ActionCandidate candidate = new ActionCandidate(
                entityId, ActionType.APPLY, contactId, jobId,
                Instant.now(), null, 0.7
        );

        ScoredAction result = scorer.score(candidate);

        assertThat(result.entityId()).isEqualTo(entityId);
        assertThat(result.type()).isEqualTo(ActionType.APPLY);
        assertThat(result.contactId()).isEqualTo(contactId);
        assertThat(result.jobId()).isEqualTo(jobId);
    }
}
