package dev.jobhunter.people.service;

import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.RelationshipEvent;
import dev.jobhunter.people.model.enums.EventType;
import dev.jobhunter.people.model.enums.RelationshipStatus;
import dev.jobhunter.people.repository.RelationshipEventRepository;
import dev.jobhunter.people.repository.RelationshipRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages relationship lifecycle with contacts via an event-sourced state machine.
 */
@Slf4j
@Service
public class RelationshipService {

    private final RelationshipRepository relationshipRepository;
    private final RelationshipEventRepository eventRepository;
    private final OutreachContactRepository contactRepository;

    public RelationshipService(RelationshipRepository relationshipRepository,
                               RelationshipEventRepository eventRepository,
                               OutreachContactRepository contactRepository) {
        this.relationshipRepository = relationshipRepository;
        this.eventRepository = eventRepository;
        this.contactRepository = contactRepository;
    }

    @Transactional
    public Relationship getOrCreate(UUID contactId) {
        return relationshipRepository.findByContactId(contactId)
                .orElseGet(() -> {
                    var contact = contactRepository.findById(contactId)
                            .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + contactId));
                    var relationship = Relationship.builder()
                            .contact(contact)
                            .status(RelationshipStatus.DISCOVERED)
                            .build();
                    return relationshipRepository.save(relationship);
                });
    }

    @Transactional
    public RelationshipEvent recordEvent(UUID relationshipId, EventType type, Map<String, Object> metadata) {
        Relationship relationship = relationshipRepository.findById(relationshipId)
                .orElseThrow(() -> new IllegalArgumentException("Relationship not found: " + relationshipId));

        RelationshipEvent event = RelationshipEvent.builder()
                .relationship(relationship)
                .eventType(type)
                .metadata(metadata)
                .occurredAt(LocalDateTime.now())
                .build();
        event = eventRepository.save(event);

        applyTransition(relationship, type, metadata);
        relationshipRepository.save(relationship);

        log.debug("Recorded event {} for relationship {}, new status: {}",
                type, relationshipId, relationship.getStatus());
        return event;
    }

    public Page<Relationship> getByStatus(RelationshipStatus status, Pageable page) {
        return relationshipRepository.findByStatus(status, page);
    }

    public List<RelationshipEvent> getEvents(UUID relationshipId) {
        return eventRepository.findByRelationshipIdOrderByOccurredAtDesc(relationshipId);
    }

    /**
     * Finds CONTACTED relationships with no reply past threshold days,
     * transitions them to GHOSTED, records a GHOSTED_AUTO event.
     * Idempotent: already-GHOSTED relationships are not re-processed.
     */
    @Transactional
    public int detectGhosting(int thresholdDays) {
        LocalDateTime threshold = LocalDateTime.now().minusDays(thresholdDays);
        List<Relationship> stale = relationshipRepository.findGhostCandidates(threshold);

        int count = 0;
        for (Relationship rel : stale) {
            rel.setStatus(RelationshipStatus.GHOSTED);
            relationshipRepository.save(rel);

            RelationshipEvent event = RelationshipEvent.builder()
                    .relationship(rel)
                    .eventType(EventType.GHOSTED_AUTO)
                    .metadata(Map.of("thresholdDays", thresholdDays))
                    .occurredAt(LocalDateTime.now())
                    .build();
            eventRepository.save(event);
            count++;
        }

        if (count > 0) {
            log.info("Detected {} ghosted relationships (threshold: {} days)", count, thresholdDays);
        }
        return count;
    }

    /**
     * Rebuilds relationship status from its event history (latest event wins).
     */
    @Transactional
    public void recomputeStatus(UUID relationshipId) {
        Relationship relationship = relationshipRepository.findById(relationshipId)
                .orElseThrow(() -> new IllegalArgumentException("Relationship not found: " + relationshipId));

        List<RelationshipEvent> events = eventRepository
                .findByRelationshipIdOrderByOccurredAtDesc(relationshipId);

        // Reset to initial state
        relationship.setStatus(RelationshipStatus.DISCOVERED);
        relationship.setLastContactAt(null);
        relationship.setLastReplyAt(null);
        relationship.setInterviewObtained(false);

        // Replay events in chronological order (reverse the DESC list)
        List<RelationshipEvent> chronological = events.reversed();
        for (RelationshipEvent event : chronological) {
            applyTransition(relationship, event.getEventType(), event.getMetadata());
        }

        relationshipRepository.save(relationship);
        log.debug("Recomputed status for relationship {}: {}", relationshipId, relationship.getStatus());
    }

    private void applyTransition(Relationship relationship, EventType type, Map<String, Object> metadata) {
        switch (type) {
            case CONTACT_DISCOVERED -> {} // stays DISCOVERED
            case MESSAGE_SENT -> {
                relationship.setStatus(RelationshipStatus.CONTACTED);
                relationship.setLastContactAt(LocalDateTime.now());
            }
            case REPLIED -> {
                relationship.setStatus(RelationshipStatus.REPLIED);
                relationship.setLastReplyAt(LocalDateTime.now());
            }
            case CALL_BOOKED -> relationship.setStatus(RelationshipStatus.ENGAGED);
            case REFERRAL_REQUESTED -> {} // status unchanged
            case REFERRAL_GIVEN -> relationship.setStatus(RelationshipStatus.REFERRED);
            case INTERVIEW_OBTAINED -> {
                relationship.setStatus(RelationshipStatus.INTERVIEW_OBTAINED);
                relationship.setInterviewObtained(true);
            }
            case GHOSTED_AUTO -> relationship.setStatus(RelationshipStatus.GHOSTED);
            case STATUS_OVERRIDE -> {
                if (metadata != null && metadata.containsKey("newStatus")) {
                    String newStatus = String.valueOf(metadata.get("newStatus"));
                    try {
                        relationship.setStatus(RelationshipStatus.valueOf(newStatus));
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid status override value: {}", newStatus);
                    }
                }
            }
        }
    }
}
