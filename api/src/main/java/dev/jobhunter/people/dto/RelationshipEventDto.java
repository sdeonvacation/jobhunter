package dev.jobhunter.people.dto;

import dev.jobhunter.people.model.enums.EventType;
import java.util.Map;

public record RelationshipEventDto(
    String id,
    EventType eventType,
    String occurredAt,
    Map<String, Object> metadata
) {}
