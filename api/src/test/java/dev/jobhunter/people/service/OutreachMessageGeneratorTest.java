package dev.jobhunter.people.service;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.people.ai.AiTask;
import dev.jobhunter.people.ai.GeneratedMessage;
import dev.jobhunter.people.ai.MessageVariant;
import dev.jobhunter.people.ai.OutreachContext;
import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.enums.RelationshipStatus;
import dev.jobhunter.people.repository.RelationshipRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import dev.jobhunter.service.PersonalProfileLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutreachMessageGeneratorTest {

    @Mock
    private OutreachContextAssembler contextAssembler;
    @Mock
    private AiProvider aiProvider;
    @Mock
    private OutreachContactRepository contactRepository;
    @Mock
    private RelationshipRepository relationshipRepository;
    @Mock
    private JobPostingRepository jobPostingRepository;

    private OutreachMessageGenerator generator;

    @BeforeEach
    void setUp() {
        AiTask<OutreachContext, GeneratedMessage> task = new InfoChatTask();
        generator = new OutreachMessageGenerator(
                contextAssembler, aiProvider, contactRepository,
                relationshipRepository, jobPostingRepository,
                List.of(task)
        );
    }

    @Test
    void generate_happyPath_returnsGeneratedMessage() {
        UUID contactId = UUID.randomUUID();
        OutreachContact contact = OutreachContact.builder()
                .id(contactId)
                .personName("Test Contact")
                .linkedinUrl("https://linkedin.com/in/test")
                .build();

        Relationship relationship = Relationship.builder()
                .id(UUID.randomUUID())
                .contact(contact)
                .status(RelationshipStatus.DISCOVERED)
                .build();

        OutreachContext mockContext = new OutreachContext(
                contact, relationship, List.of(), List.of(), null, null, MessageVariant.INFO_CHAT);

        when(contactRepository.findById(contactId)).thenReturn(Optional.of(contact));
        when(relationshipRepository.findByContactId(contactId)).thenReturn(Optional.of(relationship));
        when(contextAssembler.assemble(contact, relationship, null, MessageVariant.INFO_CHAT))
                .thenReturn(mockContext);
        when(aiProvider.generate(anyString(), anyString())).thenReturn("Generated message content");
        when(aiProvider.name()).thenReturn("openai");

        GeneratedMessage result = generator.generate(contactId, MessageVariant.INFO_CHAT, null);

        assertThat(result.content()).isEqualTo("Generated message content");
        assertThat(result.variant()).isEqualTo(MessageVariant.INFO_CHAT);
        assertThat(result.contactId()).isEqualTo(contactId);
        assertThat(result.modelUsed()).isEqualTo("openai");
        assertThat(result.tokensUsed()).isGreaterThan(0);
    }

    @Test
    void generate_contactNotFound_throwsException() {
        UUID contactId = UUID.randomUUID();
        when(contactRepository.findById(contactId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> generator.generate(contactId, MessageVariant.INFO_CHAT, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Contact not found");
    }

    @Test
    void generate_noTaskForVariant_throwsException() {
        UUID contactId = UUID.randomUUID();
        OutreachContact contact = OutreachContact.builder()
                .id(contactId)
                .personName("Test")
                .linkedinUrl("https://linkedin.com/in/test")
                .build();

        when(contactRepository.findById(contactId)).thenReturn(Optional.of(contact));
        when(relationshipRepository.findByContactId(contactId)).thenReturn(Optional.empty());

        // REFERRAL_ASK has no registered task
        assertThatThrownBy(() -> generator.generate(contactId, MessageVariant.REFERRAL_ASK, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No AI task registered for variant");
    }

    @Test
    void generate_withJobId_loadsJob() {
        UUID contactId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        OutreachContact contact = OutreachContact.builder()
                .id(contactId)
                .personName("Test")
                .linkedinUrl("https://linkedin.com/in/test")
                .build();

        JobPosting job = JobPosting.builder()
                .id(jobId)
                .title("Backend Engineer")
                .build();

        OutreachContext mockContext = new OutreachContext(
                contact, null, List.of(), List.of(), job, null, MessageVariant.INFO_CHAT);

        when(contactRepository.findById(contactId)).thenReturn(Optional.of(contact));
        when(relationshipRepository.findByContactId(contactId)).thenReturn(Optional.empty());
        when(jobPostingRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(contextAssembler.assemble(contact, null, job, MessageVariant.INFO_CHAT))
                .thenReturn(mockContext);
        when(aiProvider.generate(anyString(), anyString())).thenReturn("Message about the role");
        when(aiProvider.name()).thenReturn("anthropic");

        GeneratedMessage result = generator.generate(contactId, MessageVariant.INFO_CHAT, jobId);

        assertThat(result.content()).isEqualTo("Message about the role");
        assertThat(result.jobId()).isEqualTo(jobId);
        verify(jobPostingRepository).findById(jobId);
    }

    @Test
    void getAvailableVariants_discoveredStatus_returnsInfoTechRecruiter() {
        UUID contactId = UUID.randomUUID();
        when(relationshipRepository.findByContactId(contactId)).thenReturn(Optional.empty());

        List<MessageVariant> variants = generator.getAvailableVariants(contactId);

        assertThat(variants).containsExactly(
                MessageVariant.INFO_CHAT,
                MessageVariant.TECH_DISCUSSION,
                MessageVariant.RECRUITER_PITCH
        );
    }

    @Test
    void getAvailableVariants_contactedStatus_returnsFollowUpTechReferral() {
        UUID contactId = UUID.randomUUID();
        Relationship relationship = Relationship.builder()
                .id(UUID.randomUUID())
                .status(RelationshipStatus.CONTACTED)
                .build();
        when(relationshipRepository.findByContactId(contactId)).thenReturn(Optional.of(relationship));

        List<MessageVariant> variants = generator.getAvailableVariants(contactId);

        assertThat(variants).containsExactly(
                MessageVariant.FOLLOW_UP,
                MessageVariant.TECH_DISCUSSION,
                MessageVariant.REFERRAL_ASK
        );
    }

    @Test
    void getAvailableVariants_repliedStatus_returnsFollowUpTechReferral() {
        UUID contactId = UUID.randomUUID();
        Relationship relationship = Relationship.builder()
                .id(UUID.randomUUID())
                .status(RelationshipStatus.REPLIED)
                .build();
        when(relationshipRepository.findByContactId(contactId)).thenReturn(Optional.of(relationship));

        List<MessageVariant> variants = generator.getAvailableVariants(contactId);

        assertThat(variants).containsExactly(
                MessageVariant.FOLLOW_UP,
                MessageVariant.TECH_DISCUSSION,
                MessageVariant.REFERRAL_ASK
        );
    }

    @Test
    void getAvailableVariants_engagedStatus_returnsReferralAndFollowUp() {
        UUID contactId = UUID.randomUUID();
        Relationship relationship = Relationship.builder()
                .id(UUID.randomUUID())
                .status(RelationshipStatus.ENGAGED)
                .build();
        when(relationshipRepository.findByContactId(contactId)).thenReturn(Optional.of(relationship));

        List<MessageVariant> variants = generator.getAvailableVariants(contactId);

        assertThat(variants).containsExactly(
                MessageVariant.REFERRAL_ASK,
                MessageVariant.FOLLOW_UP
        );
    }

    @Test
    void getAvailableVariants_ghostedStatus_returnsFollowUpOnly() {
        UUID contactId = UUID.randomUUID();
        Relationship relationship = Relationship.builder()
                .id(UUID.randomUUID())
                .status(RelationshipStatus.GHOSTED)
                .build();
        when(relationshipRepository.findByContactId(contactId)).thenReturn(Optional.of(relationship));

        List<MessageVariant> variants = generator.getAvailableVariants(contactId);

        assertThat(variants).containsExactly(MessageVariant.FOLLOW_UP);
    }

    /**
     * Stub that follows naming convention: class name InfoChatTask -> INFO_CHAT variant.
     */
    static class InfoChatTask implements AiTask<OutreachContext, GeneratedMessage> {
        @Override
        public String systemPrompt(OutreachContext input) {
            return "system prompt";
        }

        @Override
        public String userPrompt(OutreachContext input) {
            return "user prompt";
        }

        @Override
        public GeneratedMessage parseResponse(String raw, OutreachContext input) {
            return new GeneratedMessage(
                    raw.strip(),
                    MessageVariant.INFO_CHAT,
                    input.contact().getId(),
                    input.targetJob() != null ? input.targetJob().getId() : null,
                    null,
                    0
            );
        }
    }
}
