package dev.jobhunter.people.ai;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.enums.Direction;
import dev.jobhunter.people.model.enums.RelationshipStatus;
import dev.jobhunter.service.PersonalProfile;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TechDiscussionTaskTest {

    private final TechDiscussionTask task = new TechDiscussionTask();

    @Test
    void systemPrompt_enforcesCharLimit() {
        OutreachContext ctx = buildContext();
        String prompt = task.systemPrompt(ctx);
        assertThat(prompt).contains("350 characters");
    }

    @Test
    void systemPrompt_requiresPeerTone() {
        OutreachContext ctx = buildContext();
        String prompt = task.systemPrompt(ctx);
        assertThat(prompt).containsIgnoringCase("peer");
    }

    @Test
    void userPrompt_identifiesSharedTechStack() {
        OutreachContext ctx = buildContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("Shared technologies");
        assertThat(prompt).contains("Java");
    }

    @Test
    void userPrompt_handlesNoSharedTech() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Ruby Dev")
                .linkedinUrl("https://linkedin.com/in/ruby")
                .title("Ruby Engineer")
                .techStack(List.of("Ruby", "Rails"))
                .build();

        PersonalProfile profile = new PersonalProfile(
                "Java Dev", "Backend", 5,
                List.of(new PersonalProfile.ProfileSkill("Java", "expert", "language")),
                null, null, null, null, null
        );

        OutreachContext ctx = new OutreachContext(contact, null, List.of(), List.of(), null, profile, MessageVariant.TECH_DISCUSSION);
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).doesNotContain("Shared technologies");
    }

    @Test
    void userPrompt_includesJobDescription_whenPresent() {
        OutreachContext ctx = buildContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("Relevant job posting");
    }

    @Test
    void parseResponse_setsCorrectVariant() {
        OutreachContext ctx = buildContext();
        GeneratedMessage result = task.parseResponse("Interesting approach to event sourcing", ctx);
        assertThat(result.variant()).isEqualTo(MessageVariant.TECH_DISCUSSION);
    }

    @Test
    void parseResponse_truncatesAt350() {
        OutreachContext ctx = buildContext();
        String longText = "a".repeat(500);
        GeneratedMessage result = task.parseResponse(longText, ctx);
        assertThat(result.content()).hasSize(350);
        assertThat(result.content()).endsWith("...");
    }

    private OutreachContext buildContext() {
        Company company = new Company();
        company.setName("TechCo");

        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Tech Lead")
                .linkedinUrl("https://linkedin.com/in/techlead")
                .title("Staff Engineer")
                .company(company)
                .techStack(List.of("Java", "Kafka", "gRPC"))
                .build();

        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Senior Backend Engineer")
                .company(company)
                .externalId("ext-2")
                .description("We use Java, Spring Boot, Kafka, and Kubernetes for our microservices platform.")
                .build();

        PersonalProfile profile = new PersonalProfile(
                "Sender", "Backend Engineer", 6,
                List.of(
                        new PersonalProfile.ProfileSkill("Java", "expert", "language"),
                        new PersonalProfile.ProfileSkill("Kafka", "advanced", "messaging"),
                        new PersonalProfile.ProfileSkill("Spring Boot", "expert", "framework")
                ),
                null, null, null, null, null
        );

        return new OutreachContext(contact, null, List.of(), List.of(), job, profile, MessageVariant.TECH_DISCUSSION);
    }
}
