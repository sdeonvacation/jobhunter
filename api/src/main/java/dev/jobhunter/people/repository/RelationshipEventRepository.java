package dev.jobhunter.people.repository;

import dev.jobhunter.people.model.RelationshipEvent;
import dev.jobhunter.people.model.enums.EventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RelationshipEventRepository extends JpaRepository<RelationshipEvent, UUID> {

    List<RelationshipEvent> findByRelationshipIdOrderByOccurredAtDesc(UUID relationshipId);

    List<RelationshipEvent> findByRelationshipIdAndEventType(UUID relationshipId, EventType eventType);

    boolean existsByRelationshipIdAndEventType(UUID relationshipId, EventType eventType);
}
