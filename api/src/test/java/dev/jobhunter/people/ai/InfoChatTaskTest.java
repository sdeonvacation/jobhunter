package dev.jobhunter.people.ai;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.RelationshipEvent;
import dev.jobhunter.people.model.enums.Direction;
import dev.jobhunter.people.model.enums.EventType;
import dev.jobhunter.people.model.enums.RelationshipStatus;
import dev.jobhunter.service.PersonalProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InfoChatTaskTest {

    private final InfoChatTask task = new InfoChatTask();

    @Test
    void systemPrompt_containsCharacterLimit() {
        OutreachContext ctx = buildMinimalContext();
        String prompt = task.systemPrompt(ctx);
        assertThat(prompt).contains("300 characters");
    }

    @Test
    void systemPrompt_prohibitsGenericCompliments() {
        OutreachContext ctx = buildMinimalContext();
        String prompt = task.systemPrompt(ctx);
        assertThat(prompt).containsIgnoringCase("no compliments");
    }

    @Test
    void userPrompt_includesContactInfo() {
        OutreachContext ctx = buildFullContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("Jane Engineer");
        assertThat(prompt).contains("Senior Developer");
        assertThat(prompt).contains("Acme Corp");
    }

    @Test
    void userPrompt_includesTechStack() {
        OutreachContext ctx = buildFullContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("Java");
        assertThat(prompt).contains("Kubernetes");
    }

    @Test
    void userPrompt_includesSenderProfile() {
        OutreachContext ctx = buildFullContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("John Dev");
        assertThat(prompt).contains("Backend Engineer");
    }

    @Test
    void userPrompt_includesJobContext_whenPresent() {
        OutreachContext ctx = buildFullContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("Platform Engineer");
    }

    @Test
    void userPrompt_handlesNullJob() {
        OutreachContext ctx = buildContextWithoutJob();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).doesNotContain("Target job context");
    }

    @Test
    void userPrompt_includesMessageHistory() {
        OutreachContext ctx = buildFullContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("Previous messages");
        assertThat(prompt).contains("OUT");
    }

    @Test
    void parseResponse_wrapsInGeneratedMessage() {
        OutreachContext ctx = buildFullContext();
        GeneratedMessage result = task.parseResponse("What's your experience scaling K8s?", ctx);

        assertThat(result.content()).isEqualTo("What's your experience scaling K8s?");
        assertThat(result.variant()).isEqualTo(MessageVariant.INFO_CHAT);
        assertThat(result.contactId()).isEqualTo(ctx.contact().getId());
        assertThat(result.jobId()).isEqualTo(ctx.targetJob().getId());
    }

    @Test
    void parseResponse_truncatesLongContent() {
        OutreachContext ctx = buildMinimalContext();
        String longText = "x".repeat(400);
        GeneratedMessage result = task.parseResponse(longText, ctx);

        assertThat(result.content()).hasSize(300);
        assertThat(result.content()).endsWith("...");
    }

    @Test
    void parseResponse_stripsWhitespace() {
        OutreachContext ctx = buildMinimalContext();
        GeneratedMessage result = task.parseResponse("  hello  \n", ctx);
        assertThat(result.content()).isEqualTo("hello");
    }

    @Test
    void parseResponse_nullJobId_whenNoTargetJob() {
        OutreachContext ctx = buildContextWithoutJob();
        GeneratedMessage result = task.parseResponse("test", ctx);
        assertThat(result.jobId()).isNull();
    }

    private OutreachContext buildMinimalContext() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Min Contact")
                .linkedinUrl("https://linkedin.com/in/min")
                .title("Dev")
                .build();

        PersonalProfile profile = new PersonalProfile(
                "Sender", "Dev", 5, List.of(),
                null, null, null, null, null
        );

        return new OutreachContext(contact, null, List.of(), List.of(), null, profile, MessageVariant.INFO_CHAT);
    }

    private OutreachContext buildFullContext() {
        Company company = new Company();
        company.setName("Acme Corp");

        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Jane Engineer")
                .linkedinUrl("https://linkedin.com/in/jane")
                .title("Senior Developer")
                .company(company)
                .techStack(List.of("Java", "Kubernetes", "PostgreSQL"))
                .location("Berlin")
                .build();

        Relationship relationship = Relationship.builder()
                .id(UUID.randomUUID())
                .contact(contact)
                .status(RelationshipStatus.ENGAGED)
                .lastContactAt(LocalDateTime.now().minusDays(7))
                .responseRate(0.5)
                .build();

        RelationshipEvent event = RelationshipEvent.builder()
                .id(UUID.randomUUID())
                .relationship(relationship)
                .eventType(EventType.MESSAGE_SENT)
                .occurredAt(LocalDateTime.now().minusDays(7))
                .build();

        OutreachMessage message = OutreachMessage.builder()
                .id(UUID.randomUUID())
                .contact(contact)
                .direction(Direction.OUT)
                .content("Hey, saw your Kubernetes talk.")
                .sentAt(LocalDateTime.now().minusDays(7))
                .build();

        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Platform Engineer")
                .company(company)
                .externalId("ext-1")
                .build();

        PersonalProfile profile = new PersonalProfile(
                "John Dev", "Backend Engineer", 6,
                List.of(
                        new PersonalProfile.ProfileSkill("Java", "expert", "language"),
                        new PersonalProfile.ProfileSkill("Spring Boot", "advanced", "framework"),
                        new PersonalProfile.ProfileSkill("Kubernetes", "intermediate", "infra")
                ),
                null, null, null, null, null
        );

        return new OutreachContext(contact, relationship, List.of(event), List.of(message), job, profile, MessageVariant.INFO_CHAT);
    }

    private OutreachContext buildContextWithoutJob() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("No Job Contact")
                .linkedinUrl("https://linkedin.com/in/nojob")
                .title("Engineer")
                .build();

        PersonalProfile profile = new PersonalProfile(
                "Sender", "Dev", 5, List.of(),
                null, null, null, null, null
        );

        return new OutreachContext(contact, null, List.of(), List.of(), null, profile, MessageVariant.INFO_CHAT);
    }
}
