package dev.jobhunter.controller;

import dev.jobhunter.linkedin.ConnectionStatus;
import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.people.dto.*;
import dev.jobhunter.people.model.ContactDiscoveryRun;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.RelationshipEvent;
import dev.jobhunter.people.model.enums.*;
import dev.jobhunter.people.repository.RelationshipRepository;
import dev.jobhunter.people.service.ContactDiscoveryService;
import dev.jobhunter.people.service.ContactPriorityScorer;
import dev.jobhunter.people.service.RelationshipService;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PeopleControllerTest {

    @Mock private RelationshipService relationshipService;
    @Mock private ContactDiscoveryService contactDiscoveryService;
    @Mock private ContactPriorityScorer contactPriorityScorer;
    @Mock private OutreachContactRepository outreachContactRepository;
    @Mock private RelationshipRepository relationshipRepository;
    @Mock private JobPostingRepository jobPostingRepository;

    private PeopleController controller;

    @BeforeEach
    void setUp() {
        controller = new PeopleController(
                relationshipService,
                contactDiscoveryService,
                contactPriorityScorer,
                outreachContactRepository,
                relationshipRepository,
                jobPostingRepository
        );
    }

    @Test
    void listContacts_returnsPageOfContactDtos() {
        UUID contactId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID()).name("TestCo").build();

        OutreachContact contact = mock(OutreachContact.class);
        when(contact.getId()).thenReturn(contactId);
        when(contact.getPersonName()).thenReturn("Jane");
        when(contact.getCompany()).thenReturn(company);
        when(contact.getConnectionStatus()).thenReturn(ConnectionStatus.CONNECTED);
        when(contact.getCreatedAt()).thenReturn(LocalDateTime.now());

        Page<OutreachContact> page = new PageImpl<>(List.of(contact));
        when(outreachContactRepository.findWithFilters(any(), any(), any(), any(Pageable.class)))
                .thenReturn(page);
        when(relationshipRepository.findByContactId(contactId)).thenReturn(Optional.empty());

        Page<ContactDto> result = controller.listContacts(null, null, null, "contactPriorityScore", 0, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().personName()).isEqualTo("Jane");
    }

    @Test
    void listContacts_appliesStatusFilter() {
        Page<OutreachContact> emptyPage = new PageImpl<>(List.of());
        when(outreachContactRepository.findWithFilters(
                eq(RelationshipStatus.WARM), any(), any(), any(Pageable.class)))
                .thenReturn(emptyPage);

        Page<ContactDto> result = controller.listContacts("WARM", null, null, "contactPriorityScore", 0, 20);

        assertThat(result.getContent()).isEmpty();
        verify(outreachContactRepository).findWithFilters(
                eq(RelationshipStatus.WARM), any(), any(), any(Pageable.class));
    }

    @Test
    void getContactDetail_returnsDetailWhenFound() {
        UUID contactId = UUID.randomUUID();
        Company company = Company.builder().id(UUID.randomUUID()).name("Acme").build();

        OutreachContact contact = mock(OutreachContact.class);
        when(contact.getId()).thenReturn(contactId);
        when(contact.getPersonName()).thenReturn("Alice");
        when(contact.getCompany()).thenReturn(company);
        when(contact.getConnectionStatus()).thenReturn(ConnectionStatus.CONNECTED);
        when(contact.getCreatedAt()).thenReturn(LocalDateTime.now());
        when(contact.getLocation()).thenReturn("Berlin");
        when(contact.getTechStack()).thenReturn(List.of("Java"));
        when(contact.getReferredBy()).thenReturn(null);

        when(outreachContactRepository.findById(contactId)).thenReturn(Optional.of(contact));
        when(relationshipRepository.findByContactId(contactId)).thenReturn(Optional.empty());
        when(relationshipService.getEvents(contactId)).thenReturn(List.of());
        when(relationshipService.getMessages(contactId)).thenReturn(List.of());
        when(jobPostingRepository.findByPosterContactId(contactId)).thenReturn(List.of());

        var response = controller.getContactDetail(contactId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().contact().personName()).isEqualTo("Alice");
        assertThat(response.getBody().location()).isEqualTo("Berlin");
        assertThat(response.getBody().techStack()).containsExactly("Java");
    }

    @Test
    void getContactDetail_returns404WhenNotFound() {
        UUID contactId = UUID.randomUUID();
        when(outreachContactRepository.findById(contactId)).thenReturn(Optional.empty());

        var response = controller.getContactDetail(contactId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getContactEvents_returnsEventList() {
        UUID contactId = UUID.randomUUID();
        when(outreachContactRepository.existsById(contactId)).thenReturn(true);

        RelationshipEvent event = mock(RelationshipEvent.class);
        when(event.getId()).thenReturn(UUID.randomUUID());
        when(event.getEventType()).thenReturn(EventType.MESSAGE_SENT);
        when(event.getOccurredAt()).thenReturn(LocalDateTime.now());
        when(event.getMetadata()).thenReturn(Map.of());

        when(relationshipService.getEvents(contactId)).thenReturn(List.of(event));

        var response = controller.getContactEvents(contactId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().getFirst().eventType()).isEqualTo(EventType.MESSAGE_SENT);
    }

    @Test
    void getContactEvents_returns404WhenContactNotFound() {
        UUID contactId = UUID.randomUUID();
        when(outreachContactRepository.existsById(contactId)).thenReturn(false);

        var response = controller.getContactEvents(contactId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void recordEvent_createsAndReturnsEvent() {
        UUID contactId = UUID.randomUUID();
        Map<String, Object> metadata = Map.of("notes", "Intro call");
        RecordEventRequest request = new RecordEventRequest(EventType.MEETING, metadata);

        when(outreachContactRepository.existsById(contactId)).thenReturn(true);

        RelationshipEvent created = mock(RelationshipEvent.class);
        when(created.getId()).thenReturn(UUID.randomUUID());
        when(created.getEventType()).thenReturn(EventType.MEETING);
        when(created.getOccurredAt()).thenReturn(LocalDateTime.now());
        when(created.getMetadata()).thenReturn(metadata);

        when(relationshipService.recordEvent(contactId, EventType.MEETING, metadata)).thenReturn(created);

        var response = controller.recordEvent(contactId, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().eventType()).isEqualTo(EventType.MEETING);
        assertThat(response.getBody().metadata()).containsEntry("notes", "Intro call");
    }

    @Test
    void recordEvent_returns404WhenContactNotFound() {
        UUID contactId = UUID.randomUUID();
        RecordEventRequest request = new RecordEventRequest(EventType.NOTE, Map.of());
        when(outreachContactRepository.existsById(contactId)).thenReturn(false);

        var response = controller.recordEvent(contactId, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(relationshipService);
    }

    @Test
    void getStats_returnsAggregatedStats() {
        when(outreachContactRepository.count()).thenReturn(50L);
        when(relationshipRepository.countByStatus())
                .thenReturn(Map.of(RelationshipStatus.WARM, 20L, RelationshipStatus.COLD, 30L));
        when(outreachContactRepository.countBySeniority())
                .thenReturn(Map.of(Seniority.SENIOR, 25L, Seniority.LEAD, 15L));
        when(outreachContactRepository.averagePriorityScore()).thenReturn(72.5);
        when(outreachContactRepository.countByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(5L);

        PeopleStatsDto stats = controller.getStats();

        assertThat(stats.totalContacts()).isEqualTo(50);
        assertThat(stats.byStatus()).containsEntry(RelationshipStatus.WARM, 20L);
        assertThat(stats.bySeniority()).containsEntry(Seniority.SENIOR, 25L);
        assertThat(stats.avgPriorityScore()).isEqualTo(72.5);
        assertThat(stats.discoveredToday()).isEqualTo(5);
    }

    @Test
    void triggerDiscovery_returnsDiscoveryRunDto() {
        UUID companyId = UUID.randomUUID();
        Company company = Company.builder().id(companyId).name("DiscoverCo").build();

        ContactDiscoveryRun run = mock(ContactDiscoveryRun.class);
        when(run.getId()).thenReturn(UUID.randomUUID());
        when(run.getCompany()).thenReturn(company);
        when(run.getSource()).thenReturn(ContactDiscoverySource.LINKEDIN_SEARCH);
        when(run.getContactsFound()).thenReturn(10);
        when(run.getContactsNew()).thenReturn(7);
        when(run.getRunAt()).thenReturn(LocalDateTime.now());

        when(contactDiscoveryService.discoverForCompany(companyId)).thenReturn(run);

        var response = controller.triggerDiscovery(companyId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().companyName()).isEqualTo("DiscoverCo");
        assertThat(response.getBody().contactsNew()).isEqualTo(7);
    }

    @Test
    void getDiscoveryRuns_returnsAllRunsWhenNoFilter() {
        Company company = Company.builder().id(UUID.randomUUID()).name("Co1").build();

        ContactDiscoveryRun run = mock(ContactDiscoveryRun.class);
        when(run.getId()).thenReturn(UUID.randomUUID());
        when(run.getCompany()).thenReturn(company);
        when(run.getSource()).thenReturn(ContactDiscoverySource.LINKEDIN_SEARCH);
        when(run.getContactsFound()).thenReturn(5);
        when(run.getContactsNew()).thenReturn(3);
        when(run.getRunAt()).thenReturn(LocalDateTime.now());

        when(contactDiscoveryService.getDiscoveryRuns(null)).thenReturn(List.of(run));

        List<ContactDiscoveryRunDto> result = controller.getDiscoveryRuns(null);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().companyName()).isEqualTo("Co1");
    }

    @Test
    void getDiscoveryRuns_filtersToCompany() {
        UUID companyId = UUID.randomUUID();
        when(contactDiscoveryService.getDiscoveryRuns(companyId)).thenReturn(List.of());

        List<ContactDiscoveryRunDto> result = controller.getDiscoveryRuns(companyId.toString());

        assertThat(result).isEmpty();
        verify(contactDiscoveryService).getDiscoveryRuns(companyId);
    }

    @Test
    void getCompanyContacts_returnsContactsForCompany() {
        UUID companyId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        Company company = Company.builder().id(companyId).name("MyCo").build();

        OutreachContact contact = mock(OutreachContact.class);
        when(contact.getId()).thenReturn(contactId);
        when(contact.getPersonName()).thenReturn("Bob");
        when(contact.getCompany()).thenReturn(company);
        when(contact.getConnectionStatus()).thenReturn(ConnectionStatus.PENDING);
        when(contact.getCreatedAt()).thenReturn(LocalDateTime.now());

        when(outreachContactRepository.findByCompanyId(companyId)).thenReturn(List.of(contact));
        when(relationshipRepository.findByContactId(contactId)).thenReturn(Optional.empty());

        List<ContactDto> result = controller.getCompanyContacts(companyId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().personName()).isEqualTo("Bob");
        assertThat(result.getFirst().connectionStatus()).isEqualTo(ConnectionStatus.PENDING);
    }

    @Test
    void getCompanyContacts_returnsEmptyForUnknownCompany() {
        UUID companyId = UUID.randomUUID();
        when(outreachContactRepository.findByCompanyId(companyId)).thenReturn(List.of());

        List<ContactDto> result = controller.getCompanyContacts(companyId);

        assertThat(result).isEmpty();
    }
}
