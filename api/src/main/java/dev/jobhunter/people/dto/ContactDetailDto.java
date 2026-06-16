package dev.jobhunter.people.dto;

import java.util.List;

public record ContactDetailDto(
    ContactDto contact,
    String location,
    List<String> techStack,
    List<RelationshipEventDto> events,
    List<OutreachMessageDto> messages,
    List<LinkedJobDto> linkedJobs,
    ContactDto referredBy
) {}
