package dev.jobhunter.people.service;

import dev.jobhunter.linkedin.ConnectionStatus;
import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.Application;
import dev.jobhunter.model.enums.ApplicationStatus;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.enums.Direction;
import dev.jobhunter.people.model.enums.RelationshipStatus;
import dev.jobhunter.people.repository.OutreachMessageRepository;
import dev.jobhunter.people.repository.RelationshipRepository;
import dev.jobhunter.people.service.ActionScorer.ActionCandidate;
import dev.jobhunter.people.service.ActionScorer.ActionType;
import dev.jobhunter.people.service.ActionScorer.ScoredAction;
import dev.jobhunter.repository.ApplicationRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class OpportunityQueue {

    private static final int FOLLOW_UP_WINDOW_DAYS = 7;
    private static final int STALE_APPLICATION_DAYS = 14;
    private static final double FOLLOW_UP_BASE_IMPACT = 0.8;
    private static final double CONNECT_BASE_IMPACT = 0.6;
    private static final double STALE_APP_BASE_IMPACT = 0.5;

    private final RelationshipRepository relationshipRepository;
    private final OutreachContactRepository outreachContactRepository;
    private final OutreachMessageRepository outreachMessageRepository;
    private final ApplicationRepository applicationRepository;
    private final ActionScorer actionScorer;

    public OpportunityQueue(RelationshipRepository relationshipRepository,
                            OutreachContactRepository outreachContactRepository,
                            OutreachMessageRepository outreachMessageRepository,
                            ApplicationRepository applicationRepository,
                            ActionScorer actionScorer) {
        this.relationshipRepository = relationshipRepository;
        this.outreachContactRepository = outreachContactRepository;
        this.outreachMessageRepository = outreachMessageRepository;
        this.applicationRepository = applicationRepository;
        this.actionScorer = actionScorer;
    }

    public List<ScoredAction> getToday(int limit) {
        List<ActionCandidate> candidates = new ArrayList<>();

        candidates.addAll(gatherFollowUpCandidates());
        candidates.addAll(gatherHighPriorityUnconnected());
        candidates.addAll(gatherStaleApplications());

        return candidates.stream()
                .map(actionScorer::score)
                .sorted(Comparator.comparingDouble(ScoredAction::actionScore).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    private List<ActionCandidate> gatherFollowUpCandidates() {
        List<Relationship> replied = relationshipRepository
                .findByStatus(RelationshipStatus.REPLIED, PageRequest.of(0, 100))
                .getContent();

        List<ActionCandidate> candidates = new ArrayList<>();
        for (Relationship rel : replied) {
            UUID contactId = rel.getContact().getId();
            List<OutreachMessage> messages = outreachMessageRepository.findByContactIdOrderBySentAtDesc(contactId);

            boolean hasRecentOutbound = messages.stream()
                    .filter(m -> m.getDirection() == Direction.OUT)
                    .anyMatch(m -> m.getSentAt().isAfter(LocalDateTime.now().minusDays(FOLLOW_UP_WINDOW_DAYS)));

            if (!hasRecentOutbound) {
                Instant lastActivity = rel.getLastReplyAt() != null
                        ? rel.getLastReplyAt().toInstant(ZoneOffset.UTC)
                        : rel.getLastContactAt() != null
                        ? rel.getLastContactAt().toInstant(ZoneOffset.UTC)
                        : Instant.now().minus(FOLLOW_UP_WINDOW_DAYS, ChronoUnit.DAYS);

                Instant deadline = lastActivity.plus(FOLLOW_UP_WINDOW_DAYS, ChronoUnit.DAYS);

                candidates.add(new ActionCandidate(
                        rel.getId(),
                        ActionType.FOLLOW_UP,
                        contactId,
                        null,
                        lastActivity,
                        deadline,
                        FOLLOW_UP_BASE_IMPACT
                ));
            }
        }
        return candidates;
    }

    private List<ActionCandidate> gatherHighPriorityUnconnected() {
        List<OutreachContact> topContacts = outreachContactRepository
                .findAllOrderByPriorityDesc(PageRequest.of(0, 50))
                .getContent();

        return topContacts.stream()
                .filter(c -> c.getConnectionStatus() == ConnectionStatus.NONE)
                .filter(c -> c.getContactPriorityScore() > 50)
                .map(contact -> {
                    Instant created = contact.getCreatedAt() != null
                            ? contact.getCreatedAt().toInstant(ZoneOffset.UTC)
                            : Instant.now();
                    Instant deadline = created.plus(14, ChronoUnit.DAYS);

                    return new ActionCandidate(
                            contact.getId(),
                            ActionType.CONNECT,
                            contact.getId(),
                            null,
                            created,
                            deadline,
                            CONNECT_BASE_IMPACT * (contact.getContactPriorityScore() / 100.0)
                    );
                })
                .collect(Collectors.toList());
    }

    private List<ActionCandidate> gatherStaleApplications() {
        List<Application> activeApps = Stream.of(ApplicationStatus.APPLIED, ApplicationStatus.PHONE_SCREEN, ApplicationStatus.INTERVIEWING)
                .flatMap(status -> applicationRepository.findByStatus(status).stream())
                .toList();

        LocalDateTime staleThreshold = LocalDateTime.now().minusDays(STALE_APPLICATION_DAYS);

        return activeApps.stream()
                .filter(app -> app.getUpdatedAt() != null && app.getUpdatedAt().isBefore(staleThreshold))
                .map(app -> {
                    Instant lastUpdate = app.getUpdatedAt().toInstant(ZoneOffset.UTC);
                    Instant deadline = lastUpdate.plus(STALE_APPLICATION_DAYS, ChronoUnit.DAYS);

                    return new ActionCandidate(
                            app.getId(),
                            ActionType.FOLLOW_UP,
                            null,
                            app.getJob() != null ? app.getJob().getId() : null,
                            lastUpdate,
                            deadline,
                            STALE_APP_BASE_IMPACT
                    );
                })
                .collect(Collectors.toList());
    }
}
