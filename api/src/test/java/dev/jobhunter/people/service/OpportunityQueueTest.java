package dev.jobhunter.people.service;

import dev.jobhunter.linkedin.ConnectionStatus;
import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.Application;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.ApplicationStatus;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.enums.Direction;
import dev.jobhunter.people.model.enums.RelationshipStatus;
import dev.jobhunter.people.repository.OutreachMessageRepository;
import dev.jobhunter.people.repository.RelationshipRepository;
import dev.jobhunter.people.service.ActionScorer.ActionType;
import dev.jobhunter.people.service.ActionScorer.ScoredAction;
import dev.jobhunter.repository.ApplicationRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpportunityQueueTest {

    @Mock
    private RelationshipRepository relationshipRepository;
    @Mock
    private OutreachContactRepository outreachContactRepository;
    @Mock
    private OutreachMessageRepository outreachMessageRepository;
    @Mock
    private ApplicationRepository applicationRepository;

    private ActionScorer actionScorer;
    private OpportunityQueue queue;

    @BeforeEach
    void setUp() {
        actionScorer = new ActionScorer();
        queue = new OpportunityQueue(relationshipRepository, outreachContactRepository,
                outreachMessageRepository, applicationRepository, actionScorer);
    }

    @Test
    void getToday_noData_returnsEmptyList() {
        when(relationshipRepository.findByStatus(eq(RelationshipStatus.REPLIED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(outreachContactRepository.findAllOrderByPriorityDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(applicationRepository.findByStatus(ApplicationStatus.APPLIED)).thenReturn(List.of());
        when(applicationRepository.findByStatus(ApplicationStatus.PHONE_SCREEN)).thenReturn(List.of());
        when(applicationRepository.findByStatus(ApplicationStatus.INTERVIEWING)).thenReturn(List.of());

        List<ScoredAction> result = queue.getToday(10);

        assertThat(result).isEmpty();
    }

    @Test
    void getToday_followUpOverdue_generatesFollowUpAction() {
        UUID contactId = UUID.randomUUID();
        OutreachContact contact = OutreachContact.builder()
                .id(contactId).personName("Recruiter").build();
        Relationship rel = Relationship.builder()
                .id(UUID.randomUUID())
                .contact(contact)
                .status(RelationshipStatus.REPLIED)
                .lastReplyAt(LocalDateTime.now().minusDays(10))
                .build();

        when(relationshipRepository.findByStatus(eq(RelationshipStatus.REPLIED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(rel)));
        when(outreachMessageRepository.findByContactIdOrderBySentAtDesc(contactId))
                .thenReturn(List.of()); // no recent messages
        when(outreachContactRepository.findAllOrderByPriorityDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(applicationRepository.findByStatus(ApplicationStatus.APPLIED)).thenReturn(List.of());
        when(applicationRepository.findByStatus(ApplicationStatus.PHONE_SCREEN)).thenReturn(List.of());
        when(applicationRepository.findByStatus(ApplicationStatus.INTERVIEWING)).thenReturn(List.of());

        List<ScoredAction> result = queue.getToday(10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo(ActionType.FOLLOW_UP);
        assertThat(result.get(0).contactId()).isEqualTo(contactId);
    }

    @Test
    void getToday_recentMessage_doesNotGenerateFollowUp() {
        UUID contactId = UUID.randomUUID();
        OutreachContact contact = OutreachContact.builder()
                .id(contactId).personName("Recruiter").build();
        Relationship rel = Relationship.builder()
                .id(UUID.randomUUID())
                .contact(contact)
                .status(RelationshipStatus.REPLIED)
                .lastReplyAt(LocalDateTime.now().minusDays(3))
                .build();

        OutreachMessage recentMsg = OutreachMessage.builder()
                .id(UUID.randomUUID())
                .contact(contact)
                .direction(Direction.OUT)
                .sentAt(LocalDateTime.now().minusDays(2))
                .build();

        when(relationshipRepository.findByStatus(eq(RelationshipStatus.REPLIED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(rel)));
        when(outreachMessageRepository.findByContactIdOrderBySentAtDesc(contactId))
                .thenReturn(List.of(recentMsg));
        when(outreachContactRepository.findAllOrderByPriorityDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(applicationRepository.findByStatus(ApplicationStatus.APPLIED)).thenReturn(List.of());
        when(applicationRepository.findByStatus(ApplicationStatus.PHONE_SCREEN)).thenReturn(List.of());
        when(applicationRepository.findByStatus(ApplicationStatus.INTERVIEWING)).thenReturn(List.of());

        List<ScoredAction> result = queue.getToday(10);

        assertThat(result).isEmpty();
    }

    @Test
    void getToday_highPriorityUnconnected_generatesConnectAction() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Senior Recruiter")
                .connectionStatus(ConnectionStatus.NONE)
                .contactPriorityScore(80)
                .createdAt(LocalDateTime.now().minusDays(5))
                .build();

        when(relationshipRepository.findByStatus(eq(RelationshipStatus.REPLIED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(outreachContactRepository.findAllOrderByPriorityDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(contact)));
        when(applicationRepository.findByStatus(ApplicationStatus.APPLIED)).thenReturn(List.of());
        when(applicationRepository.findByStatus(ApplicationStatus.PHONE_SCREEN)).thenReturn(List.of());
        when(applicationRepository.findByStatus(ApplicationStatus.INTERVIEWING)).thenReturn(List.of());

        List<ScoredAction> result = queue.getToday(10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo(ActionType.CONNECT);
    }

    @Test
    void getToday_alreadyConnected_excluded() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Connected Person")
                .connectionStatus(ConnectionStatus.CONNECTED)
                .contactPriorityScore(90)
                .createdAt(LocalDateTime.now().minusDays(2))
                .build();

        when(relationshipRepository.findByStatus(eq(RelationshipStatus.REPLIED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(outreachContactRepository.findAllOrderByPriorityDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(contact)));
        when(applicationRepository.findByStatus(ApplicationStatus.APPLIED)).thenReturn(List.of());
        when(applicationRepository.findByStatus(ApplicationStatus.PHONE_SCREEN)).thenReturn(List.of());
        when(applicationRepository.findByStatus(ApplicationStatus.INTERVIEWING)).thenReturn(List.of());

        List<ScoredAction> result = queue.getToday(10);

        assertThat(result).isEmpty();
    }

    @Test
    void getToday_staleApplication_generatesFollowUp() {
        UUID jobId = UUID.randomUUID();
        JobPosting job = JobPosting.builder().id(jobId).build();
        Application staleApp = Application.builder()
                .id(UUID.randomUUID())
                .job(job)
                .status(ApplicationStatus.APPLIED)
                .updatedAt(LocalDateTime.now().minusDays(20))
                .build();

        when(relationshipRepository.findByStatus(eq(RelationshipStatus.REPLIED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(outreachContactRepository.findAllOrderByPriorityDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(applicationRepository.findByStatus(ApplicationStatus.APPLIED)).thenReturn(List.of(staleApp));
        when(applicationRepository.findByStatus(ApplicationStatus.PHONE_SCREEN)).thenReturn(List.of());
        when(applicationRepository.findByStatus(ApplicationStatus.INTERVIEWING)).thenReturn(List.of());

        List<ScoredAction> result = queue.getToday(10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo(ActionType.FOLLOW_UP);
        assertThat(result.get(0).jobId()).isEqualTo(jobId);
    }

    @Test
    void getToday_limitsResults() {
        OutreachContact contact1 = OutreachContact.builder()
                .id(UUID.randomUUID()).personName("A").connectionStatus(ConnectionStatus.NONE)
                .contactPriorityScore(90).createdAt(LocalDateTime.now().minusDays(3)).build();
        OutreachContact contact2 = OutreachContact.builder()
                .id(UUID.randomUUID()).personName("B").connectionStatus(ConnectionStatus.NONE)
                .contactPriorityScore(80).createdAt(LocalDateTime.now().minusDays(3)).build();
        OutreachContact contact3 = OutreachContact.builder()
                .id(UUID.randomUUID()).personName("C").connectionStatus(ConnectionStatus.NONE)
                .contactPriorityScore(70).createdAt(LocalDateTime.now().minusDays(3)).build();

        when(relationshipRepository.findByStatus(eq(RelationshipStatus.REPLIED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(outreachContactRepository.findAllOrderByPriorityDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(contact1, contact2, contact3)));
        when(applicationRepository.findByStatus(ApplicationStatus.APPLIED)).thenReturn(List.of());
        when(applicationRepository.findByStatus(ApplicationStatus.PHONE_SCREEN)).thenReturn(List.of());
        when(applicationRepository.findByStatus(ApplicationStatus.INTERVIEWING)).thenReturn(List.of());

        List<ScoredAction> result = queue.getToday(2);

        assertThat(result).hasSize(2);
    }

    @Test
    void getToday_sortsByActionScoreDescending() {
        UUID contactId1 = UUID.randomUUID();
        UUID contactId2 = UUID.randomUUID();
        OutreachContact contact1 = OutreachContact.builder()
                .id(contactId1).personName("Old Reply")
                .build();
        OutreachContact contact2 = OutreachContact.builder()
                .id(contactId2).personName("Recent Reply")
                .build();

        // contact1: replied 10 days ago = more urgent
        Relationship rel1 = Relationship.builder()
                .id(UUID.randomUUID()).contact(contact1)
                .status(RelationshipStatus.REPLIED)
                .lastReplyAt(LocalDateTime.now().minusDays(10))
                .build();
        // contact2: replied 8 days ago = slightly less urgent
        Relationship rel2 = Relationship.builder()
                .id(UUID.randomUUID()).contact(contact2)
                .status(RelationshipStatus.REPLIED)
                .lastReplyAt(LocalDateTime.now().minusDays(8))
                .build();

        when(relationshipRepository.findByStatus(eq(RelationshipStatus.REPLIED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(rel1, rel2)));
        when(outreachMessageRepository.findByContactIdOrderBySentAtDesc(contactId1)).thenReturn(List.of());
        when(outreachMessageRepository.findByContactIdOrderBySentAtDesc(contactId2)).thenReturn(List.of());
        when(outreachContactRepository.findAllOrderByPriorityDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(applicationRepository.findByStatus(ApplicationStatus.APPLIED)).thenReturn(List.of());
        when(applicationRepository.findByStatus(ApplicationStatus.PHONE_SCREEN)).thenReturn(List.of());
        when(applicationRepository.findByStatus(ApplicationStatus.INTERVIEWING)).thenReturn(List.of());

        List<ScoredAction> result = queue.getToday(10);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).actionScore()).isGreaterThanOrEqualTo(result.get(1).actionScore());
    }
}
