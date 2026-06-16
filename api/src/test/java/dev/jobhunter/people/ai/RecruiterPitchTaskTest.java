package dev.jobhunter.people.ai;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.people.model.enums.Direction;
import dev.jobhunter.service.PersonalProfile;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecruiterPitchTaskTest {

    private final RecruiterPitchTask task = new RecruiterPitchTask();

    @Test
    void systemPrompt_enforcesCharLimit() {
        OutreachContext ctx = buildContext();
        String prompt = task.systemPrompt(ctx);
        assertThat(prompt).contains("350 characters");
    }

    @Test
    void systemPrompt_requiresCTA() {
        OutreachContext ctx = buildContext();
        String prompt = task.systemPrompt(ctx);
        assertThat(prompt).containsIgnoringCase("call-to-action");
    }

    @Test
    void systemPrompt_targetsProfessionalTone() {
        OutreachContext ctx = buildContext();
        String prompt = task.systemPrompt(ctx);
        assertThat(prompt).containsIgnoringCase("professional");
    }

    @Test
    void userPrompt_labelsAsRecruiter() {
        OutreachContext ctx = buildContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("Recruiter/HM:");
    }

    @Test
    void userPrompt_includesCandidateProfile() {
        OutreachContext ctx = buildContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("Candidate profile");
        assertThat(prompt).contains("Years experience: 7");
    }

    @Test
    void userPrompt_includesTopSkills() {
        OutreachContext ctx = buildContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("Top skills");
        assertThat(prompt).contains("Java");
    }

    @Test
    void userPrompt_includesOpenRole() {
        OutreachContext ctx = buildContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("Their open role");
        assertThat(prompt).contains("Senior Java Developer");
    }

    @Test
    void userPrompt_includesLocationPreferences() {
        OutreachContext ctx = buildContext();
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).contains("Target locations");
        assertThat(prompt).contains("Berlin");
    }

    @Test
    void parseResponse_setsCorrectVariant() {
        OutreachContext ctx = buildContext();
        GeneratedMessage result = task.parseResponse("I match your Java role well", ctx);
        assertThat(result.variant()).isEqualTo(MessageVariant.RECRUITER_PITCH);
    }

    @Test
    void parseResponse_truncatesAt350() {
        OutreachContext ctx = buildContext();
        String longText = "d".repeat(500);
        GeneratedMessage result = task.parseResponse(longText, ctx);
        assertThat(result.content()).hasSize(350);
        assertThat(result.content()).endsWith("...");
    }

    @Test
    void parseResponse_setsJobId() {
        OutreachContext ctx = buildContext();
        GeneratedMessage result = task.parseResponse("pitch", ctx);
        assertThat(result.jobId()).isEqualTo(ctx.targetJob().getId());
    }

    @Test
    void userPrompt_handlesNullTargetJob() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Recruiter")
                .linkedinUrl("https://linkedin.com/in/recruiter")
                .title("Technical Recruiter")
                .build();

        PersonalProfile profile = new PersonalProfile(
                "Dev", "Backend", 5,
                List.of(new PersonalProfile.ProfileSkill("Java", "expert", "language")),
                new PersonalProfile.Preferences(List.of("Munich"), "FULL_TIME", 70000, List.of("senior"), List.of("en"), List.of()),
                null, null, null, null
        );

        OutreachContext ctx = new OutreachContext(contact, null, List.of(), List.of(), null, profile, MessageVariant.RECRUITER_PITCH);
        String prompt = task.userPrompt(ctx);
        assertThat(prompt).doesNotContain("Their open role");
    }

    private OutreachContext buildContext() {
        Company company = new Company();
        company.setName("HiringCo");

        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID())
                .personName("Sarah Recruiter")
                .linkedinUrl("https://linkedin.com/in/sarah")
                .title("Technical Recruiter")
                .company(company)
                .build();

        JobPosting job = JobPosting.builder()
                .id(UUID.randomUUID())
                .title("Senior Java Developer")
                .company(company)
                .externalId("ext-5")
                .location("Berlin, Germany")
                .description("Looking for 5+ years Java, Spring Boot, microservices experience.")
                .build();

        PersonalProfile profile = new PersonalProfile(
                "Candidate", "Backend Engineer", 7,
                List.of(
                        new PersonalProfile.ProfileSkill("Java", "expert", "language"),
                        new PersonalProfile.ProfileSkill("Spring Boot", "advanced", "framework"),
                        new PersonalProfile.ProfileSkill("Kubernetes", "intermediate", "infra")
                ),
                new PersonalProfile.Preferences(List.of("Berlin", "Munich"), "FULL_TIME", 80000, List.of("senior"), List.of("en", "de"), List.of()),
                null, null, null, null
        );

        return new OutreachContext(contact, null, List.of(), List.of(), job, profile, MessageVariant.RECRUITER_PITCH);
    }
}
