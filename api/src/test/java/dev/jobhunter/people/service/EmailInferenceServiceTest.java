package dev.jobhunter.people.service;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.people.dto.EmailSuggestion;
import dev.jobhunter.people.model.enums.EmailConfidence;
import dev.jobhunter.repository.OutreachContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailInferenceServiceTest {

    @Mock
    private OutreachContactRepository contactRepository;

    private EmailInferenceService service;

    @BeforeEach
    void setUp() {
        service = new EmailInferenceService(contactRepository);
    }

    @Test
    void inferEmails_standardName_generatesAllPatterns() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("John Smith")
                .linkedinUrl("https://linkedin.com/in/john-smith")
                .build();

        List<EmailSuggestion> suggestions = service.inferEmails(contact, "acme.com");

        assertThat(suggestions).hasSize(5);
        assertThat(suggestions.get(0).email()).isEqualTo("john.smith@acme.com");
        assertThat(suggestions.get(0).confidence()).isEqualTo(EmailConfidence.HIGH);
        assertThat(suggestions.get(1).email()).isEqualTo("john@acme.com");
        assertThat(suggestions.get(1).confidence()).isEqualTo(EmailConfidence.MEDIUM);
        assertThat(suggestions.get(2).email()).isEqualTo("j.smith@acme.com");
        assertThat(suggestions.get(2).confidence()).isEqualTo(EmailConfidence.MEDIUM);
        assertThat(suggestions.get(3).email()).isEqualTo("jsmith@acme.com");
        assertThat(suggestions.get(3).confidence()).isEqualTo(EmailConfidence.LOW);
        assertThat(suggestions.get(4).email()).isEqualTo("john_smith@acme.com");
        assertThat(suggestions.get(4).confidence()).isEqualTo(EmailConfidence.LOW);
    }

    @Test
    void inferEmails_multiWordLastName_joinsTokens() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Anna van der Berg")
                .linkedinUrl("https://linkedin.com/in/anna-vdb")
                .build();

        List<EmailSuggestion> suggestions = service.inferEmails(contact, "company.de");

        assertThat(suggestions.get(0).email()).isEqualTo("anna.vanderberg@company.de");
        assertThat(suggestions.get(2).email()).isEqualTo("a.vanderberg@company.de");
    }

    @Test
    void inferEmails_nullDomain_returnsEmpty() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("John Smith")
                .linkedinUrl("https://linkedin.com/in/js")
                .build();

        List<EmailSuggestion> suggestions = service.inferEmails(contact, null);

        assertThat(suggestions).isEmpty();
    }

    @Test
    void inferEmails_blankDomain_returnsEmpty() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("John Smith")
                .linkedinUrl("https://linkedin.com/in/js")
                .build();

        List<EmailSuggestion> suggestions = service.inferEmails(contact, "  ");

        assertThat(suggestions).isEmpty();
    }

    @Test
    void inferEmails_nullName_returnsEmpty() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName(null)
                .linkedinUrl("https://linkedin.com/in/unknown")
                .build();

        List<EmailSuggestion> suggestions = service.inferEmails(contact, "acme.com");

        assertThat(suggestions).isEmpty();
    }

    @Test
    void inferEmails_singleNameOnly_returnsEmpty() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Madonna")
                .linkedinUrl("https://linkedin.com/in/madonna")
                .build();

        List<EmailSuggestion> suggestions = service.inferEmails(contact, "acme.com");

        assertThat(suggestions).isEmpty();
    }

    @Test
    void inferEmails_nameWithSpecialChars_sanitizes() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("José García (He/Him)")
                .linkedinUrl("https://linkedin.com/in/jose-garcia")
                .build();

        List<EmailSuggestion> suggestions = service.inferEmails(contact, "startup.io");

        assertThat(suggestions.get(0).email()).isEqualTo("jos.garca@startup.io");
    }

    @Test
    void inferEmails_domainWithSpaces_trims() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("John Smith")
                .linkedinUrl("https://linkedin.com/in/js")
                .build();

        List<EmailSuggestion> suggestions = service.inferEmails(contact, " ACME.COM ");

        assertThat(suggestions.get(0).email()).isEqualTo("john.smith@acme.com");
    }

    @Test
    void inferAndSave_noExistingEmail_savesHighestConfidence() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Jane Doe")
                .linkedinUrl("https://linkedin.com/in/jane-doe")
                .email(null)
                .emailConfidence(EmailConfidence.NONE)
                .build();

        when(contactRepository.save(any())).thenReturn(contact);

        service.inferAndSave(contact, "example.com");

        assertThat(contact.getEmail()).isEqualTo("jane.doe@example.com");
        assertThat(contact.getEmailConfidence()).isEqualTo(EmailConfidence.HIGH);
        verify(contactRepository).save(contact);
    }

    @Test
    void inferAndSave_existingEmail_skips() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Jane Doe")
                .linkedinUrl("https://linkedin.com/in/jane-doe")
                .email("jane@verified.com")
                .emailConfidence(EmailConfidence.HIGH)
                .build();

        service.inferAndSave(contact, "example.com");

        verify(contactRepository, never()).save(any());
    }

    @Test
    void inferAndSave_noDomain_doesNotSave() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Jane Doe")
                .linkedinUrl("https://linkedin.com/in/jane-doe")
                .build();

        service.inferAndSave(contact, null);

        verify(contactRepository, never()).save(any());
    }

    @Test
    void parseNameParts_hyphenatedLast_removesHyphen() {
        String[] parts = EmailInferenceService.parseNameParts("Mary-Jane Watson-Parker");
        assertThat(parts[0]).isEqualTo("mary-jane");
        // "Watson-Parker" -> joined: "watsonparker" (hyphen removed in lastName only)
        assertThat(parts[1]).isEqualTo("watsonparker");
    }

    @Test
    void parseNameParts_extraWhitespace_normalizes() {
        String[] parts = EmailInferenceService.parseNameParts("  John    Smith  ");
        assertThat(parts[0]).isEqualTo("john");
        assertThat(parts[1]).isEqualTo("smith");
    }
}
