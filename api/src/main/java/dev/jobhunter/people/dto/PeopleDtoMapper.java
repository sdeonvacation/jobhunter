package dev.jobhunter.people.dto;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.people.model.ContactDiscoveryRun;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.RelationshipEvent;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Maps people-module entities to DTOs. Stateless utility.
 */
public final class PeopleDtoMapper {

    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private PeopleDtoMapper() {}

    public static ContactDto toContactDto(OutreachContact contact, Relationship relationship) {
        return new ContactDto(
                contact.getId().toString(),
                contact.getPersonName(),
                contact.getTitle(),
                contact.getLinkedinUrl(),
                contact.getCompany() != null ? contact.getCompany().getId().toString() : null,
                contact.getCompany() != null ? contact.getCompany().getName() : null,
                contact.getSeniority(),
                contact.getDiscoveredVia(),
                contact.getConnectionStatus(),
                contact.getInterviewGenerationWeight() != null ? contact.getInterviewGenerationWeight() : 0,
                contact.getWarmthScore() != null ? contact.getWarmthScore() : 0,
                contact.getContactPriorityScore() != null ? contact.getContactPriorityScore() : 0,
                relationship != null ? relationship.getStatus() : null,
                relationship != null && relationship.getLastContactAt() != null
                        ? relationship.getLastContactAt().format(ISO_FORMAT) : null,
                contact.getCreatedAt() != null ? contact.getCreatedAt().format(ISO_FORMAT) : null
        );
    }

    public static ContactDetailDto toContactDetailDto(OutreachContact contact,
                                                      Relationship relationship,
                                                      List<RelationshipEvent> events,
                                                      List<OutreachMessage> messages,
                                                      List<JobPosting> linkedJobs,
                                                      OutreachContact referredBy) {
        ContactDto contactDto = toContactDto(contact, relationship);
        List<RelationshipEventDto> eventDtos = events != null
                ? events.stream().map(PeopleDtoMapper::toRelationshipEventDto).toList()
                : List.of();
        List<OutreachMessageDto> messageDtos = messages != null
                ? messages.stream().map(PeopleDtoMapper::toOutreachMessageDto).toList()
                : List.of();
        List<LinkedJobDto> jobDtos = linkedJobs != null
                ? linkedJobs.stream().map(PeopleDtoMapper::toLinkedJobDto).toList()
                : List.of();
        ContactDto referredByDto = referredBy != null ? toContactDto(referredBy, null) : null;

        return new ContactDetailDto(
                contactDto,
                contact.getLocation(),
                contact.getTechStack() != null ? contact.getTechStack() : List.of(),
                eventDtos,
                messageDtos,
                jobDtos,
                referredByDto
        );
    }

    public static RelationshipEventDto toRelationshipEventDto(RelationshipEvent event) {
        return new RelationshipEventDto(
                event.getId().toString(),
                event.getEventType(),
                event.getOccurredAt() != null ? event.getOccurredAt().format(ISO_FORMAT) : null,
                event.getMetadata()
        );
    }

    public static OutreachMessageDto toOutreachMessageDto(OutreachMessage message) {
        return new OutreachMessageDto(
                message.getId().toString(),
                message.getDirection(),
                message.getChannel(),
                message.getMessageType(),
                message.getContent(),
                message.getSentAt() != null ? message.getSentAt().format(ISO_FORMAT) : null,
                message.getReplied() != null && message.getReplied(),
                message.getRepliedAt() != null ? message.getRepliedAt().format(ISO_FORMAT) : null
        );
    }

    public static ContactDiscoveryRunDto toContactDiscoveryRunDto(ContactDiscoveryRun run) {
        return new ContactDiscoveryRunDto(
                run.getId().toString(),
                run.getCompany() != null ? run.getCompany().getId().toString() : null,
                run.getCompany() != null ? run.getCompany().getName() : null,
                run.getSource(),
                run.getContactsFound(),
                run.getContactsNew(),
                run.getRunAt() != null ? run.getRunAt().format(ISO_FORMAT) : null
        );
    }

    public static LinkedJobDto toLinkedJobDto(JobPosting job) {
        return new LinkedJobDto(
                job.getId().toString(),
                job.getTitle(),
                job.getCompany() != null ? job.getCompany().getName() : null,
                job.getLocation(),
                job.getPostedDate() != null ? job.getPostedDate().toString() : null
        );
    }
}
