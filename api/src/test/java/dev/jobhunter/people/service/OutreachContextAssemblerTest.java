package dev.jobhunter.people.service;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.people.ai.MessageVariant;
import dev.jobhunter.people.ai.OutreachContext;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.RelationshipEvent;
import dev.jobhunter.people.model.enums.RelationshipStatus;
import dev.jobhunter.people.repository.OutreachMessageRepository;
import dev.jobhunter.people.repository.RelationshipEventRepository;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutreachContextAssemblerTest {

    @Mock
    private RelationshipEventRepository eventRepository;
    @Mock
    private OutreachMessageRepository messageRepository;
    @Mock
    private PersonalProfileLoader profileLoader;

    private OutreachContextAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new OutreachContextAssembler(eventRepository, messageRepository, profileLoader);
    }

    @Test
    void assemble_withRelationship_loadsEventsAndMessages() {
        UUID contactId = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();

        OutreachContact contact = OutreachContact.builder()
                .id(contactId)
                .personName("Jane Doe")
                .linkedinUrl("https://linkedin.com/in/jane")
                .build();

        Relationship relationship = Relationship.builder()
                .id(relationshipId)
                .contact(contact)
                .status(RelationshipStatus.CONTACTED)
                .build();

        RelationshipEvent event = RelationshipEvent.builder()
                .id(UUID.randomUUID())
                .relationship(relationship)
                .build();

        OutreachMessage message = OutreachMessage.builder()
                .id(UUID.randomUUID())
                .contact(contact)
                .content("Hello")
                .build();

        PersonalProfile profile = new PersonalProfile(
                "Test User", "Engineer", 5, List.of(),
                null, null, null, null, null);

        when(eventRepository.findByRelationshipIdOrderByOccurredAtDesc(relationshipId))
                .thenReturn(List.of(event));
        when(messageRepository.findByContactIdOrderBySentAtDesc(contactId))
                .thenReturn(List.of(message));
        when(profileLoader.getProfile()).thenReturn(profile);

        OutreachContext ctx = assembler.assemble(contact, relationship, null, MessageVariant.INFO_CHAT);

        assertThat(ctx.contact()).isEqualTo(contact);
        assertThat(ctx.relationship()).isEqualTo(relationship);
        assertThat(ctx.events()).hasSize(1);
        assertThat(ctx.messageHistory()).hasSize(1);
        assertThat(ctx.targetJob()).isNull();
        assertThat(ctx.userProfile()).isEqualTo(profile);
        assertThat(ctx.variant()).isEqualTo(MessageVariant.INFO_CHAT);
    }

    @Test
    void assemble_withNullRelationship_returnsEmptyEvents() {
        UUID contactId = UUID.randomUUID();

        OutreachContact contact = OutreachContact.builder()
                .id(contactId)
                .personName("No Relationship")
                .linkedinUrl("https://linkedin.com/in/no-rel")
                .build();

        PersonalProfile profile = new PersonalProfile(
                "Test User", "Engineer", 5, List.of(),
                null, null, null, null, null);

        when(messageRepository.findByContactIdOrderBySentAtDesc(contactId))
                .thenReturn(List.of());
        when(profileLoader.getProfile()).thenReturn(profile);

        OutreachContext ctx = assembler.assemble(contact, null, null, MessageVariant.TECH_DISCUSSION);

        assertThat(ctx.events()).isEmpty();
        assertThat(ctx.messageHistory()).isEmpty();
        assertThat(ctx.relationship()).isNull();
    }

    @Test
    void assemble_withTargetJob_includesJob() {
        UUID contactId = UUID.randomUUID();

        OutreachContact contact = OutreachContact.builder()
                .id(contactId)
                .personName("Contact")
                .linkedinUrl("https://linkedin.com/in/contact")
                .build();

        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Senior Engineer")
                .build();

        PersonalProfile profile = new PersonalProfile(
                "Test User", "Engineer", 5, List.of(),
                null, null, null, null, null);

        when(messageRepository.findByContactIdOrderBySentAtDesc(contactId))
                .thenReturn(List.of());
        when(profileLoader.getProfile()).thenReturn(profile);

        OutreachContext ctx = assembler.assemble(contact, null, job, MessageVariant.REFERRAL_ASK);

        assertThat(ctx.targetJob()).isEqualTo(job);
        assertThat(ctx.variant()).isEqualTo(MessageVariant.REFERRAL_ASK);
    }
}
