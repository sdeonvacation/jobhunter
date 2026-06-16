package dev.jobhunter.people.service;

import dev.jobhunter.linkedin.LinkedInNetworkingService;
import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.Company;
import dev.jobhunter.people.dto.ContactScore;
import dev.jobhunter.people.model.ContactDiscoveryRun;
import dev.jobhunter.people.repository.ContactDiscoveryRunRepository;
import dev.jobhunter.repository.CompanyRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContactDiscoveryServiceTest {

    @Mock
    private LinkedInNetworkingService networkingService;
    @Mock
    private OutreachContactRepository contactRepository;
    @Mock
    private ContactDiscoveryRunRepository discoveryRunRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private ContactPriorityScorer scorer;

    private ContactDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new ContactDiscoveryService(
                networkingService, contactRepository, discoveryRunRepository, companyRepository, scorer);
    }

    @Test
    void discoverForCompany_successfulDiscovery_recordsRun() {
        UUID companyId = UUID.randomUUID();
        List<String> keywords = List.of("recruiter", "hiring manager");
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Found Person")
                .build();

        when(companyRepository.getReferenceById(companyId)).thenReturn(Company.builder().id(companyId).build());
        when(networkingService.findContacts(companyId, keywords)).thenReturn(List.of(contact));
        when(contactRepository.findByCompanyId(companyId)).thenReturn(List.of());
        when(scorer.scoreBatch(any())).thenReturn(List.of(
                new ContactScore(contact.getId(), 50, 20, 38)
        ));
        when(discoveryRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<OutreachContact> result = service.discoverForCompany(companyId, keywords);

        assertThat(result).hasSize(1);

        ArgumentCaptor<ContactDiscoveryRun> captor = ArgumentCaptor.forClass(ContactDiscoveryRun.class);
        verify(discoveryRunRepository).save(captor.capture());
        ContactDiscoveryRun run = captor.getValue();
        assertThat(run.getContactsFound()).isEqualTo(1);
        assertThat(run.getContactsNew()).isEqualTo(1);
        assertThat(run.getDurationMs()).isNotNull();
        assertThat(run.getErrorMessage()).isNull();
    }

    @Test
    void discoverForCompany_existingContacts_countsNewCorrectly() {
        UUID companyId = UUID.randomUUID();
        OutreachContact existing = OutreachContact.builder().id(UUID.randomUUID()).build();
        OutreachContact newContact = OutreachContact.builder().id(UUID.randomUUID()).build();

        when(companyRepository.getReferenceById(companyId)).thenReturn(Company.builder().id(companyId).build());
        when(networkingService.findContacts(eq(companyId), any())).thenReturn(List.of(newContact, existing));
        when(contactRepository.findByCompanyId(companyId)).thenReturn(List.of(existing));
        when(scorer.scoreBatch(any())).thenReturn(List.of());
        when(discoveryRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.discoverForCompany(companyId, List.of("recruiter"));

        ArgumentCaptor<ContactDiscoveryRun> captor = ArgumentCaptor.forClass(ContactDiscoveryRun.class);
        verify(discoveryRunRepository).save(captor.capture());
        assertThat(captor.getValue().getContactsFound()).isEqualTo(2);
        assertThat(captor.getValue().getContactsNew()).isEqualTo(1);
    }

    @Test
    void discoverForCompany_networkingServiceThrows_recordsError() {
        UUID companyId = UUID.randomUUID();

        when(companyRepository.getReferenceById(companyId)).thenReturn(Company.builder().id(companyId).build());
        when(networkingService.findContacts(eq(companyId), any()))
                .thenThrow(new RuntimeException("Rate limited"));
        when(discoveryRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<OutreachContact> result = service.discoverForCompany(companyId, List.of("recruiter"));

        assertThat(result).isEmpty();

        ArgumentCaptor<ContactDiscoveryRun> captor = ArgumentCaptor.forClass(ContactDiscoveryRun.class);
        verify(discoveryRunRepository).save(captor.capture());
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("Rate limited");
    }

    @Test
    void discoverTopPriority_skipsCompaniesWithExistingContacts() {
        Company companyWithContacts = Company.builder().id(UUID.randomUUID()).name("Has Contacts").build();
        Company companyWithout = Company.builder().id(UUID.randomUUID()).name("No Contacts").build();

        when(companyRepository.findByIsActiveTrue(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(companyWithContacts, companyWithout)));
        when(contactRepository.findByCompanyId(companyWithContacts.getId()))
                .thenReturn(List.of(OutreachContact.builder().build()));
        when(contactRepository.findByCompanyId(companyWithout.getId()))
                .thenReturn(List.of());
        when(companyRepository.getReferenceById(companyWithout.getId())).thenReturn(companyWithout);
        when(networkingService.findContacts(eq(companyWithout.getId()), any())).thenReturn(List.of());
        when(discoveryRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int result = service.discoverTopPriority(5);

        assertThat(result).isEqualTo(0);
        // Only called for the company without contacts
        verify(networkingService).findContacts(eq(companyWithout.getId()), any());
        verify(networkingService, never()).findContacts(eq(companyWithContacts.getId()), any());
    }

    @Test
    void getDiscoveryRuns_delegatesToRepository() {
        UUID companyId = UUID.randomUUID();
        List<ContactDiscoveryRun> runs = List.of(
                ContactDiscoveryRun.builder().contactsFound(3).build()
        );

        when(discoveryRunRepository.findByCompanyIdOrderByRunAtDesc(companyId)).thenReturn(runs);

        List<ContactDiscoveryRun> result = service.getDiscoveryRuns(companyId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContactsFound()).isEqualTo(3);
    }
}
