package dev.jobhunter.people.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
public class ActionScorer {

    public ScoredAction score(ActionCandidate candidate) {
        double urgencyScore = computeUrgency(candidate);
        double actionScore = candidate.baseImpact() * urgencyScore;
        return new ScoredAction(
                candidate.entityId(),
                candidate.type(),
                candidate.contactId(),
                candidate.jobId(),
                urgencyScore,
                actionScore
        );
    }

    private double computeUrgency(ActionCandidate candidate) {
        if (candidate.deadline() == null || candidate.lastActivity() == null) {
            return 1.0;
        }

        Instant now = Instant.now();
        if (now.isAfter(candidate.deadline())) {
            return 1.2;
        }

        long totalWindowMs = Duration.between(candidate.lastActivity(), candidate.deadline()).toMillis();
        if (totalWindowMs <= 0) {
            return 1.2;
        }

        long remainingMs = Duration.between(now, candidate.deadline()).toMillis();
        double daysRemaining = remainingMs / (double) (24 * 60 * 60 * 1000);
        double totalWindow = totalWindowMs / (double) (24 * 60 * 60 * 1000);

        double raw = 1.0 - (daysRemaining / totalWindow);
        return clamp(0.0, 1.5, raw);
    }

    private double clamp(double min, double max, double value) {
        return Math.max(min, Math.min(max, value));
    }

    public record ActionCandidate(
            UUID entityId,
            ActionType type,
            UUID contactId,
            UUID jobId,
            Instant lastActivity,
            Instant deadline,
            double baseImpact
    ) {}

    public record ScoredAction(
            UUID entityId,
            ActionType type,
            UUID contactId,
            UUID jobId,
            double urgencyScore,
            double actionScore
    ) {}

    public enum ActionType {
        FOLLOW_UP, CONNECT, APPLY, PREPARE, SEND_MESSAGE
    }
}
