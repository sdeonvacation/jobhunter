package dev.jobhunter.people.dto;

import dev.jobhunter.linkedin.ConnectionStatus;
import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.people.model.ContactDiscoveryRun;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.RelationshipEvent;
import dev.jobhunter.people.model.enums.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PeopleDtoMapperTest {

    @Test
    void toContactDto_mapsAllFields() {
        UUID contactId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.of(2025, 6, 10, 14, 30);
        LocalDateTime lastContact = LocalDateTime.of(2025, 6, 12, 10, 0);

        Company company = Company.builder().id(companyId).name("Acme Corp").build();

        OutreachContact contact = mock(OutreachContact.class);
        when(contact.getId()).thenReturn(contactId);
        when(contact.getPersonName()).thenReturn("Jane Doe");
        when(contact.getTitle()).thenReturn("Engineering Manager");
        when(contact.getLinkedinUrl()).thenReturn("https://linkedin.com/in/janedoe");
        when(contact.getCompany()).thenReturn(company);
        when(contact.getSeniority()).thenReturn(Seniority.SENIOR);
        when(contact.getDiscoveredVia()).thenReturn(ContactDiscoverySource.LINKEDIN_SEARCH);
        when(contact.getConnectionStatus()).thenReturn(ConnectionStatus.CONNECTED);
        when(contact.getInterviewGenerationWeight()).thenReturn(85);
        when(contact.getWarmthScore()).thenReturn(70);
        when(contact.getContactPriorityScore()).thenReturn(92);
        when(contact.getCreatedAt()).thenReturn(createdAt);

        Relationship relationship = mock(Relationship.class);
        when(relationship.getStatus()).thenReturn(RelationshipStatus.WARM);
        when(relationship.getLastContactAt()).thenReturn(lastContact);

        ContactDto dto = PeopleDtoMapper.toContactDto(contact, relationship);

        assertThat(dto.id()).isEqualTo(contactId.toString());
        assertThat(dto.personName()).isEqualTo("Jane Doe");
        assertThat(dto.title()).isEqualTo("Engineering Manager");
        assertThat(dto.linkedinUrl()).isEqualTo("https://linkedin.com/in/janedoe");
        assertThat(dto.companyId()).isEqualTo(companyId.toString());
        assertThat(dto.companyName()).isEqualTo("Acme Corp");
        assertThat(dto.seniority()).isEqualTo(Seniority.SENIOR);
        assertThat(dto.discoveredVia()).isEqualTo(ContactDiscoverySource.LINKEDIN_SEARCH);
        assertThat(dto.connectionStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(dto.interviewGenerationWeight()).isEqualTo(85);
        assertThat(dto.warmthScore()).isEqualTo(70);
        assertThat(dto.contactPriorityScore()).isEqualTo(92);
        assertThat(dto.relationshipStatus()).isEqualTo(RelationshipStatus.WARM);
        assertThat(dto.lastContactAt()).isEqualTo("2025-06-12T10:00:00");
        assertThat(dto.createdAt()).isEqualTo("2025-06-10T14:30:00");
    }

    @Test
    void toContactDto_handlesNullRelationship() {
        UUID contactId = UUID.randomUUID();
        OutreachContact contact = mock(OutreachContact.class);
        when(contact.getId()).thenReturn(contactId);
        when(contact.getPersonName()).thenReturn("John");
        when(contact.getCompany()).thenReturn(null);
        when(contact.getConnectionStatus()).thenReturn(ConnectionStatus.NONE);
        when(contact.getCreatedAt()).thenReturn(null);

        ContactDto dto = PeopleDtoMapper.toContactDto(contact, null);

        assertThat(dto.companyId()).isNull();
        assertThat(dto.companyName()).isNull();
        assertThat(dto.relationshipStatus()).isNull();
        assertThat(dto.lastContactAt()).isNull();
        assertThat(dto.createdAt()).isNull();
    }

    @Test
    void toRelationshipEventDto_mapsAllFields() {
        UUID eventId = UUID.randomUUID();
        LocalDateTime occurred = LocalDateTime.of(2025, 5, 20, 9, 15);
        Map<String, Object> metadata = Map.of("note", "Had coffee chat");

        RelationshipEvent event = mock(RelationshipEvent.class);
        when(event.getId()).thenReturn(eventId);
        when(event.getEventType()).thenReturn(EventType.MEETING);
        when(event.getOccurredAt()).thenReturn(occurred);
        when(event.getMetadata()).thenReturn(metadata);

        RelationshipEventDto dto = PeopleDtoMapper.toRelationshipEventDto(event);

        assertThat(dto.id()).isEqualTo(eventId.toString());
        assertThat(dto.eventType()).isEqualTo(EventType.MEETING);
        assertThat(dto.occurredAt()).isEqualTo("2025-05-20T09:15:00");
        assertThat(dto.metadata()).containsEntry("note", "Had coffee chat");
    }

    @Test
    void toRelationshipEventDto_handlesNullOccurredAt() {
        RelationshipEvent event = mock(RelationshipEvent.class);
        when(event.getId()).thenReturn(UUID.randomUUID());
        when(event.getEventType()).thenReturn(EventType.NOTE);
        when(event.getOccurredAt()).thenReturn(null);
        when(event.getMetadata()).thenReturn(null);

        RelationshipEventDto dto = PeopleDtoMapper.toRelationshipEventDto(event);

        assertThat(dto.occurredAt()).isNull();
        assertThat(dto.metadata()).isNull();
    }

    @Test
    void toOutreachMessageDto_mapsAllFields() {
        UUID msgId = UUID.randomUUID();
        LocalDateTime sentAt = LocalDateTime.of(2025, 6, 1, 11, 0);
        LocalDateTime repliedAt = LocalDateTime.of(2025, 6, 2, 15, 30);

        OutreachMessage message = mock(OutreachMessage.class);
        when(message.getId()).thenReturn(msgId);
        when(message.getDirection()).thenReturn(Direction.OUTBOUND);
        when(message.getChannel()).thenReturn(Channel.LINKEDIN);
        when(message.getMessageType()).thenReturn(MessageType.CONNECTION_REQUEST);
        when(message.getContent()).thenReturn("Hi, I'd love to connect");
        when(message.getSentAt()).thenReturn(sentAt);
        when(message.isReplied()).thenReturn(true);
        when(message.getRepliedAt()).thenReturn(repliedAt);

        OutreachMessageDto dto = PeopleDtoMapper.toOutreachMessageDto(message);

        assertThat(dto.id()).isEqualTo(msgId.toString());
        assertThat(dto.direction()).isEqualTo(Direction.OUTBOUND);
        assertThat(dto.channel()).isEqualTo(Channel.LINKEDIN);
        assertThat(dto.messageType()).isEqualTo(MessageType.CONNECTION_REQUEST);
        assertThat(dto.content()).isEqualTo("Hi, I'd love to connect");
        assertThat(dto.sentAt()).isEqualTo("2025-06-01T11:00:00");
        assertThat(dto.replied()).isTrue();
        assertThat(dto.repliedAt()).isEqualTo("2025-06-02T15:30:00");
    }

    @Test
    void toOutreachMessageDto_handlesNoReply() {
        OutreachMessage message = mock(OutreachMessage.class);
        when(message.getId()).thenReturn(UUID.randomUUID());
        when(message.getDirection()).thenReturn(Direction.INBOUND);
        when(message.getChannel()).thenReturn(Channel.EMAIL);
        when(message.getMessageType()).thenReturn(MessageType.FOLLOW_UP);
        when(message.getSentAt()).thenReturn(null);
        when(message.isReplied()).thenReturn(false);
        when(message.getRepliedAt()).thenReturn(null);

        OutreachMessageDto dto = PeopleDtoMapper.toOutreachMessageDto(message);

        assertThat(dto.sentAt()).isNull();
        assertThat(dto.replied()).isFalse();
        assertThat(dto.repliedAt()).isNull();
    }

    @Test
    void toContactDiscoveryRunDto_mapsAllFields() {
        UUID runId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        LocalDateTime runAt = LocalDateTime.of(2025, 6, 14, 8, 0);
        Company company = Company.builder().id(companyId).name("TechCo").build();

        ContactDiscoveryRun run = mock(ContactDiscoveryRun.class);
        when(run.getId()).thenReturn(runId);
        when(run.getCompany()).thenReturn(company);
        when(run.getSource()).thenReturn(ContactDiscoverySource.LINKEDIN_SEARCH);
        when(run.getContactsFound()).thenReturn(12);
        when(run.getContactsNew()).thenReturn(8);
        when(run.getRunAt()).thenReturn(runAt);

        ContactDiscoveryRunDto dto = PeopleDtoMapper.toContactDiscoveryRunDto(run);

        assertThat(dto.id()).isEqualTo(runId.toString());
        assertThat(dto.companyId()).isEqualTo(companyId.toString());
        assertThat(dto.companyName()).isEqualTo("TechCo");
        assertThat(dto.source()).isEqualTo(ContactDiscoverySource.LINKEDIN_SEARCH);
        assertThat(dto.contactsFound()).isEqualTo(12);
        assertThat(dto.contactsNew()).isEqualTo(8);
        assertThat(dto.runAt()).isEqualTo("2025-06-14T08:00:00");
    }

    @Test
    void toContactDiscoveryRunDto_handlesNullCompany() {
        ContactDiscoveryRun run = mock(ContactDiscoveryRun.class);
        when(run.getId()).thenReturn(UUID.randomUUID());
        when(run.getCompany()).thenReturn(null);
        when(run.getSource()).thenReturn(ContactDiscoverySource.LINKEDIN_SEARCH);
        when(run.getContactsFound()).thenReturn(0);
        when(run.getContactsNew()).thenReturn(0);
        when(run.getRunAt()).thenReturn(null);

        ContactDiscoveryRunDto dto = PeopleDtoMapper.toContactDiscoveryRunDto(run);

        assertThat(dto.companyId()).isNull();
        assertThat(dto.companyName()).isNull();
        assertThat(dto.runAt()).isNull();
    }

    @Test
    void toLinkedJobDto_mapsAllFields() {
        UUID jobId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID()).name("StartupX").build();

        JobPosting job = JobPosting.builder()
                .id(jobId)
                .title("Senior Backend Engineer")
                .company(company)
                .location("Munich, Germany")
                .postedDate(LocalDate.of(2025, 6, 10))
                .build();

        LinkedJobDto dto = PeopleDtoMapper.toLinkedJobDto(job);

        assertThat(dto.id()).isEqualTo(jobId.toString());
        assertThat(dto.title()).isEqualTo("Senior Backend Engineer");
        assertThat(dto.companyName()).isEqualTo("StartupX");
        assertThat(dto.location()).isEqualTo("Munich, Germany");
        assertThat(dto.postedDate()).isEqualTo("2025-06-10");
    }

    @Test
    void toLinkedJobDto_handlesNullCompanyAndDate() {
        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Developer")
                .company(null)
                .location(null)
                .postedDate(null)
                .build();

        LinkedJobDto dto = PeopleDtoMapper.toLinkedJobDto(job);

        assertThat(dto.companyName()).isNull();
        assertThat(dto.location()).isNull();
        assertThat(dto.postedDate()).isNull();
    }

    @Test
    void toContactDetailDto_assemblesAllParts() {
        UUID contactId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID()).name("BigCo").build();

        OutreachContact contact = mock(OutreachContact.class);
        when(contact.getId()).thenReturn(contactId);
        when(contact.getPersonName()).thenReturn("Alice");
        when(contact.getCompany()).thenReturn(company);
        when(contact.getConnectionStatus()).thenReturn(ConnectionStatus.CONNECTED);
        when(contact.getCreatedAt()).thenReturn(LocalDateTime.of(2025, 1, 1, 0, 0));
        when(contact.getLocation()).thenReturn("Berlin, Germany");
        when(contact.getTechStack()).thenReturn(List.of("Java", "Spring", "Kafka"));
        when(contact.getSeniority()).thenReturn(Seniority.LEAD);
        when(contact.getDiscoveredVia()).thenReturn(ContactDiscoverySource.LINKEDIN_SEARCH);

        Relationship relationship = mock(Relationship.class);
        when(relationship.getStatus()).thenReturn(RelationshipStatus.ACTIVE);
        when(relationship.getLastContactAt()).thenReturn(LocalDateTime.of(2025, 6, 1, 12, 0));

        RelationshipEvent event = mock(RelationshipEvent.class);
        when(event.getId()).thenReturn(UUID.randomUUID());
        when(event.getEventType()).thenReturn(EventType.MESSAGE_SENT);
        when(event.getOccurredAt()).thenReturn(LocalDateTime.of(2025, 6, 1, 12, 0));
        when(event.getMetadata()).thenReturn(Map.of());

        OutreachMessage message = mock(OutreachMessage.class);
        when(message.getId()).thenReturn(UUID.randomUUID());
        when(message.getDirection()).thenReturn(Direction.OUTBOUND);
        when(message.getChannel()).thenReturn(Channel.LINKEDIN);
        when(message.getMessageType()).thenReturn(MessageType.INITIAL_OUTREACH);
        when(message.getContent()).thenReturn("Hello!");
        when(message.getSentAt()).thenReturn(LocalDateTime.of(2025, 6, 1, 12, 0));
        when(message.isReplied()).thenReturn(false);
        when(message.getRepliedAt()).thenReturn(null);

        JobPosting linkedJob = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Staff Engineer")
                .company(company)
                .location("Berlin")
                .postedDate(LocalDate.of(2025, 5, 15))
                .build();

        OutreachContact referrer = mock(OutreachContact.class);
        when(referrer.getId()).thenReturn(UUID.randomUUID());
        when(referrer.getPersonName()).thenReturn("Bob");
        when(referrer.getCompany()).thenReturn(company);
        when(referrer.getConnectionStatus()).thenReturn(ConnectionStatus.CONNECTED);
        when(referrer.getCreatedAt()).thenReturn(LocalDateTime.of(2025, 1, 5, 0, 0));

        ContactDetailDto dto = PeopleDtoMapper.toContactDetailDto(
                contact, relationship, List.of(event), List.of(message), List.of(linkedJob), referrer);

        assertThat(dto.contact().personName()).isEqualTo("Alice");
        assertThat(dto.location()).isEqualTo("Berlin, Germany");
        assertThat(dto.techStack()).containsExactly("Java", "Spring", "Kafka");
        assertThat(dto.events()).hasSize(1);
        assertThat(dto.messages()).hasSize(1);
        assertThat(dto.linkedJobs()).hasSize(1);
        assertThat(dto.linkedJobs().getFirst().title()).isEqualTo("Staff Engineer");
        assertThat(dto.referredBy()).isNotNull();
        assertThat(dto.referredBy().personName()).isEqualTo("Bob");
    }

    @Test
    void toContactDetailDto_handlesNullLists() {
        OutreachContact contact = mock(OutreachContact.class);
        when(contact.getId()).thenReturn(UUID.randomUUID());
        when(contact.getPersonName()).thenReturn("Solo");
        when(contact.getCompany()).thenReturn(null);
        when(contact.getConnectionStatus()).thenReturn(ConnectionStatus.NONE);
        when(contact.getCreatedAt()).thenReturn(null);
        when(contact.getLocation()).thenReturn(null);
        when(contact.getTechStack()).thenReturn(null);

        ContactDetailDto dto = PeopleDtoMapper.toContactDetailDto(
                contact, null, null, null, null, null);

        assertThat(dto.events()).isEmpty();
        assertThat(dto.messages()).isEmpty();
        assertThat(dto.linkedJobs()).isEmpty();
        assertThat(dto.techStack()).isEmpty();
        assertThat(dto.referredBy()).isNull();
    }
}
