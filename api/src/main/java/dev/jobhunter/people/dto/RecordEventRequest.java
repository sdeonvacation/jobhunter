package dev.jobhunter.people.dto;

import dev.jobhunter.people.model.enums.EventType;
import java.util.Map;

public record RecordEventRequest(
    EventType eventType,
    Map<String, Object> metadata
) {}
