package dev.jobhunter.people.ai;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.people.model.RelationshipEvent;
import dev.jobhunter.people.model.enums.Direction;
import dev.jobhunter.people.model.enums.EventType;
import dev.jobhunter.service.PersonalProfile;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FollowUpTaskTest {

    private final FollowUpTask task = new FollowUpTask();

    @Test
    void systemPrompt_enforcesCharLimit() {
        OutreachContext ctx = buildContext();
        String prompt = task.systemPrompt(ctx);
        assertThat(prompt).contains("250 characters");
    }

    @Test
    void systemPrompt_requiresBrevity() {
        OutreachContext ctx = buildContext();
        String prompt = task.systemPrompt(ctx);
        assertThat(prompt).containsIgnoringCase("brief");
    }

    @Test
    void systemPrompt_requiresNewValue() {
        OutreachContext ctx = buildContext();
        String prompt = task.systemPrompt(ctx);
        assertThat(prompt).containsIgnoringCase("new value");
    }

    @Test
    void userPrompt_includesConversationHistory() {
        OutreachContext ctx = buildContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("Conversation history");
        assertThat(prompt).contains("Hey, how's the migration going?");
    }

    @Test
    void userPrompt_includesRecentEvents() {
        OutreachContext ctx = buildContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("Recent events");
    }

    @Test
    void userPrompt_handlesEmptyHistory() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Silent Contact")
                .linkedinUrl("https://linkedin.com/in/silent")
                .title("Dev")
                .build();

        PersonalProfile profile = new PersonalProfile(
                "Sender", "Dev", 5, List.of(),
                null, null, null, null, null
        );

        OutreachContext ctx = new OutreachContext(contact, null, List.of(), List.of(), null, profile, MessageVariant.FOLLOW_UP);
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).doesNotContain("Conversation history");
    }

    @Test
    void parseResponse_setsCorrectVariant() {
        OutreachContext ctx = buildContext();
        GeneratedMessage result = task.parseResponse("Saw this Kafka article, thought of our chat", ctx);
        assertThat(result.variant()).isEqualTo(MessageVariant.FOLLOW_UP);
    }

    @Test
    void parseResponse_truncatesAt250() {
        OutreachContext ctx = buildContext();
        String longText = "c".repeat(300);
        GeneratedMessage result = task.parseResponse(longText, ctx);
        assertThat(result.content()).hasSize(250);
        assertThat(result.content()).endsWith("...");
    }

    @Test
    void parseResponse_preservesShortContent() {
        OutreachContext ctx = buildContext();
        GeneratedMessage result = task.parseResponse("Short follow-up", ctx);
        assertThat(result.content()).isEqualTo("Short follow-up");
    }

    private OutreachContext buildContext() {
        Company company = new Company();
        company.setName("FollowCo");

        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Follow Target")
                .linkedinUrl("https://linkedin.com/in/follow")
                .title("Tech Lead")
                .company(company)
                .techStack(List.of("Kafka", "Java"))
                .build();

        OutreachMessage msg1 = OutreachMessage.builder()
                .id(UUID.randomUUID())
                .contact(contact)
                .direction(Direction.OUT)
                .content("Hey, how's the migration going?")
                .sentAt(LocalDateTime.now().minusDays(10))
                .build();

        OutreachMessage msg2 = OutreachMessage.builder()
                .id(UUID.randomUUID())
                .contact(contact)
                .direction(Direction.IN)
                .content("Going well, we moved 3 services so far.")
                .sentAt(LocalDateTime.now().minusDays(9))
                .build();

        RelationshipEvent event = RelationshipEvent.builder()
                .id(UUID.randomUUID())
                .eventType(EventType.REPLIED)
                .occurredAt(LocalDateTime.now().minusDays(9))
                .build();

        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Backend Dev")
                .company(company)
                .externalId("ext-4")
                .build();

        PersonalProfile profile = new PersonalProfile(
                "Follower", "Engineer", 5, List.of(),
                null, null, null, null, null
        );

        return new OutreachContext(contact, null, List.of(event), List.of(msg1, msg2), job, profile, MessageVariant.FOLLOW_UP);
    }
}
