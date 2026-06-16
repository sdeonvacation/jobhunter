package dev.jobhunter.people.service;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.RelationshipEvent;
import dev.jobhunter.people.model.enums.EventType;
import dev.jobhunter.people.model.enums.RelationshipStatus;
import dev.jobhunter.people.repository.RelationshipEventRepository;
import dev.jobhunter.people.repository.RelationshipRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RelationshipServiceTest {

    @Mock
    private RelationshipRepository relationshipRepository;
    @Mock
    private RelationshipEventRepository eventRepository;
    @Mock
    private OutreachContactRepository contactRepository;

    private RelationshipService service;

    @BeforeEach
    void setUp() {
        service = new RelationshipService(relationshipRepository, eventRepository, contactRepository);
    }

    @Test
    void getOrCreate_existingRelationship_returnsIt() {
        UUID contactId = UUID.randomUUID();
        Relationship existing = Relationship.builder()
                .id(UUID.randomUUID())
                .status(RelationshipStatus.CONTACTED)
                .build();

        when(relationshipRepository.findByContactId(contactId)).thenReturn(Optional.of(existing));

        Relationship result = service.getOrCreate(contactId);

        assertThat(result).isEqualTo(existing);
        verify(relationshipRepository, never()).save(any());
    }

    @Test
    void getOrCreate_noExistingRelationship_createsNew() {
        UUID contactId = UUID.randomUUID();
        OutreachContact contact = OutreachContact.builder().id(contactId).build();

        when(relationshipRepository.findByContactId(contactId)).thenReturn(Optional.empty());
        when(contactRepository.findById(contactId)).thenReturn(Optional.of(contact));
        when(relationshipRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Relationship result = service.getOrCreate(contactId);

        assertThat(result.getStatus()).isEqualTo(RelationshipStatus.DISCOVERED);
        assertThat(result.getContact()).isEqualTo(contact);
    }

    @Test
    void getOrCreate_contactNotFound_throws() {
        UUID contactId = UUID.randomUUID();

        when(relationshipRepository.findByContactId(contactId)).thenReturn(Optional.empty());
        when(contactRepository.findById(contactId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrCreate(contactId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Contact not found");
    }

    @Test
    void recordEvent_messageSent_transitionsToContacted() {
        UUID relId = UUID.randomUUID();
        Relationship rel = Relationship.builder()
                .id(relId)
                .status(RelationshipStatus.DISCOVERED)
                .build();

        when(relationshipRepository.findById(relId)).thenReturn(Optional.of(rel));
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(relationshipRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordEvent(relId, EventType.MESSAGE_SENT, Map.of());

        assertThat(rel.getStatus()).isEqualTo(RelationshipStatus.CONTACTED);
        assertThat(rel.getLastContactAt()).isNotNull();
    }

    @Test
    void recordEvent_replied_transitionsToReplied() {
        UUID relId = UUID.randomUUID();
        Relationship rel = Relationship.builder()
                .id(relId)
                .status(RelationshipStatus.CONTACTED)
                .build();

        when(relationshipRepository.findById(relId)).thenReturn(Optional.of(rel));
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(relationshipRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordEvent(relId, EventType.REPLIED, Map.of());

        assertThat(rel.getStatus()).isEqualTo(RelationshipStatus.REPLIED);
        assertThat(rel.getLastReplyAt()).isNotNull();
    }

    @Test
    void recordEvent_callBooked_transitionsToEngaged() {
        UUID relId = UUID.randomUUID();
        Relationship rel = Relationship.builder()
                .id(relId)
                .status(RelationshipStatus.REPLIED)
                .build();

        when(relationshipRepository.findById(relId)).thenReturn(Optional.of(rel));
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(relationshipRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordEvent(relId, EventType.CALL_BOOKED, Map.of());

        assertThat(rel.getStatus()).isEqualTo(RelationshipStatus.ENGAGED);
    }

    @Test
    void recordEvent_referralGiven_transitionsToReferred() {
        UUID relId = UUID.randomUUID();
        Relationship rel = Relationship.builder()
                .id(relId)
                .status(RelationshipStatus.ENGAGED)
                .build();

        when(relationshipRepository.findById(relId)).thenReturn(Optional.of(rel));
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(relationshipRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordEvent(relId, EventType.REFERRAL_GIVEN, Map.of());

        assertThat(rel.getStatus()).isEqualTo(RelationshipStatus.REFERRED);
    }

    @Test
    void recordEvent_interviewObtained_setsFlag() {
        UUID relId = UUID.randomUUID();
        Relationship rel = Relationship.builder()
                .id(relId)
                .status(RelationshipStatus.REFERRED)
                .build();

        when(relationshipRepository.findById(relId)).thenReturn(Optional.of(rel));
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(relationshipRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordEvent(relId, EventType.INTERVIEW_OBTAINED, Map.of());

        assertThat(rel.getStatus()).isEqualTo(RelationshipStatus.INTERVIEW_OBTAINED);
        assertThat(rel.isInterviewObtained()).isTrue();
    }

    @Test
    void recordEvent_statusOverride_appliesNewStatus() {
        UUID relId = UUID.randomUUID();
        Relationship rel = Relationship.builder()
                .id(relId)
                .status(RelationshipStatus.GHOSTED)
                .build();

        when(relationshipRepository.findById(relId)).thenReturn(Optional.of(rel));
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(relationshipRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordEvent(relId, EventType.STATUS_OVERRIDE, Map.of("newStatus", "CONTACTED"));

        assertThat(rel.getStatus()).isEqualTo(RelationshipStatus.CONTACTED);
    }

    @Test
    void recordEvent_referralRequested_doesNotChangeStatus() {
        UUID relId = UUID.randomUUID();
        Relationship rel = Relationship.builder()
                .id(relId)
                .status(RelationshipStatus.ENGAGED)
                .build();

        when(relationshipRepository.findById(relId)).thenReturn(Optional.of(rel));
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(relationshipRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordEvent(relId, EventType.REFERRAL_REQUESTED, Map.of());

        assertThat(rel.getStatus()).isEqualTo(RelationshipStatus.ENGAGED);
    }

    @Test
    void recordEvent_relationshipNotFound_throws() {
        UUID relId = UUID.randomUUID();
        when(relationshipRepository.findById(relId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordEvent(relId, EventType.MESSAGE_SENT, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Relationship not found");
    }

    @Test
    void detectGhosting_marksStaleRelationships() {
        Relationship stale1 = Relationship.builder()
                .id(UUID.randomUUID())
                .status(RelationshipStatus.CONTACTED)
                .lastContactAt(LocalDateTime.now().minusDays(20))
                .build();
        Relationship stale2 = Relationship.builder()
                .id(UUID.randomUUID())
                .status(RelationshipStatus.CONTACTED)
                .lastContactAt(LocalDateTime.now().minusDays(15))
                .build();

        when(relationshipRepository.findContactedBeforeThreshold(eq(RelationshipStatus.CONTACTED), any()))
                .thenReturn(List.of(stale1, stale2));
        when(relationshipRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int count = service.detectGhosting(14);

        assertThat(count).isEqualTo(2);
        assertThat(stale1.getStatus()).isEqualTo(RelationshipStatus.GHOSTED);
        assertThat(stale2.getStatus()).isEqualTo(RelationshipStatus.GHOSTED);
        verify(eventRepository, times(2)).save(any());
    }

    @Test
    void detectGhosting_noStaleRelationships_returnsZero() {
        when(relationshipRepository.findContactedBeforeThreshold(eq(RelationshipStatus.CONTACTED), any()))
                .thenReturn(List.of());

        int count = service.detectGhosting(14);

        assertThat(count).isEqualTo(0);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void getEvents_delegatesToRepository() {
        UUID relId = UUID.randomUUID();
        List<RelationshipEvent> events = List.of(
                RelationshipEvent.builder().eventType(EventType.MESSAGE_SENT).build()
        );

        when(eventRepository.findByRelationshipIdOrderByOccurredAtDesc(relId)).thenReturn(events);

        List<RelationshipEvent> result = service.getEvents(relId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventType()).isEqualTo(EventType.MESSAGE_SENT);
    }
}
