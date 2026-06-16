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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReferralAskTaskTest {

    private final ReferralAskTask task = new ReferralAskTask();

    @Test
    void systemPrompt_enforcesCharLimit() {
        OutreachContext ctx = buildContext();
        String prompt = task.systemPrompt(ctx);
        assertThat(prompt).contains("400 characters");
    }

    @Test
    void systemPrompt_requiresAppreciativeTone() {
        OutreachContext ctx = buildContext();
        String prompt = task.systemPrompt(ctx);
        assertThat(prompt).containsIgnoringCase("appreciative");
    }

    @Test
    void userPrompt_includesRelationshipContext() {
        OutreachContext ctx = buildContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("Relationship context");
        assertThat(prompt).contains("ENGAGED");
        assertThat(prompt).contains("Response rate");
    }

    @Test
    void userPrompt_includesRecentEvents() {
        OutreachContext ctx = buildContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("Recent interactions");
        assertThat(prompt).contains("MESSAGE_SENT");
    }

    @Test
    void userPrompt_includesTopSkills() {
        OutreachContext ctx = buildContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("Top skills");
        assertThat(prompt).contains("Java");
    }

    @Test
    void userPrompt_includesTargetRole() {
        OutreachContext ctx = buildContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("Target role");
        assertThat(prompt).contains("Staff Engineer");
    }

    @Test
    void userPrompt_handlesNullRelationship() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Test")
                .linkedinUrl("https://linkedin.com/in/test")
                .title("Dev")
                .build();

        PersonalProfile profile = new PersonalProfile(
                "Sender", "Dev", 5, List.of(),
                null, null, null, null, null
        );

        OutreachContext ctx = new OutreachContext(contact, null, List.of(), List.of(), null, profile, MessageVariant.REFERRAL_ASK);
        String prompt = task.userPrompt(ctx);
        // Should not throw, just skip relationship section content
        assertThat(prompt).contains("Recipient: Test");
    }

    @Test
    void parseResponse_setsCorrectVariant() {
        OutreachContext ctx = buildContext();
        GeneratedMessage result = task.parseResponse("Could you refer me?", ctx);
        assertThat(result.variant()).isEqualTo(MessageVariant.REFERRAL_ASK);
    }

    @Test
    void parseResponse_truncatesAt400() {
        OutreachContext ctx = buildContext();
        String longText = "b".repeat(500);
        GeneratedMessage result = task.parseResponse(longText, ctx);
        assertThat(result.content()).hasSize(400);
        assertThat(result.content()).endsWith("...");
    }

    private OutreachContext buildContext() {
        Company company = new Company();
        company.setName("TargetCo");

        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Friend Engineer")
                .linkedinUrl("https://linkedin.com/in/friend")
                .title("Senior Engineer")
                .company(company)
                .techStack(List.of("Java", "AWS"))
                .build();

        Relationship relationship = Relationship.builder()
                .id(UUID.randomUUID())
                .contact(contact)
                .status(RelationshipStatus.ENGAGED)
                .lastContactAt(LocalDateTime.now().minusDays(14))
                .responseRate(0.75)
                .build();

        RelationshipEvent event = RelationshipEvent.builder()
                .id(UUID.randomUUID())
                .relationship(relationship)
                .eventType(EventType.MESSAGE_SENT)
                .occurredAt(LocalDateTime.now().minusDays(14))
                .build();

        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Staff Engineer")
                .company(company)
                .externalId("ext-3")
                .location("Berlin")
                .build();

        PersonalProfile profile = new PersonalProfile(
                "Referral Seeker", "Backend Engineer", 7,
                List.of(
                        new PersonalProfile.ProfileSkill("Java", "expert", "language"),
                        new PersonalProfile.ProfileSkill("Spring Boot", "advanced", "framework"),
                        new PersonalProfile.ProfileSkill("AWS", "advanced", "cloud")
                ),
                null, null, null, null, null
        );

        OutreachMessage msg = OutreachMessage.builder()
                .id(UUID.randomUUID())
                .contact(contact)
                .direction(Direction.OUT)
                .content("Great chatting about distributed systems!")
                .sentAt(LocalDateTime.now().minusDays(14))
                .build();

        return new OutreachContext(contact, relationship, List.of(event), List.of(msg), job, profile, MessageVariant.REFERRAL_ASK);
    }
}
