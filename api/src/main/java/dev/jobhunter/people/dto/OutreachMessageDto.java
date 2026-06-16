package dev.jobhunter.people.dto;

import dev.jobhunter.people.model.enums.Channel;
import dev.jobhunter.people.model.enums.Direction;
import dev.jobhunter.people.model.enums.MessageType;

public record OutreachMessageDto(
    String id,
    Direction direction,
    Channel channel,
    MessageType messageType,
    String content,
    String sentAt,
    boolean replied,
    String repliedAt
) {}
