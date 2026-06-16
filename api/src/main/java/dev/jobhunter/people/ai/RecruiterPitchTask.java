package dev.jobhunter.people.ai;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.service.PersonalProfile;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class RecruiterPitchTask implements AiTask<OutreachContext, GeneratedMessage> {

    @Override
    public String systemPrompt(OutreachContext input) {
        return """
                You are writing a LinkedIn message on behalf of a software engineer to a recruiter or hiring manager. \
                The goal is to pitch yourself as a strong match for their open role. Rules:
                - Professional but not stiff
                - Highlight 1-2 specific qualifications that match their role
                - Include a clear call-to-action (e.g., "open to a quick chat this week?")
                - Keep it under 350 characters
                - No greeting like "Hi [Name]" — lead with the value proposition
                - Don't sound desperate or over-eager
                Output ONLY the message text, nothing else.""";
    }

    @Override
    public String userPrompt(OutreachContext input) {
        OutreachContact contact = input.contact();
        PersonalProfile profile = input.userProfile();

        StringBuilder sb = new StringBuilder();
        sb.append("Recruiter/HM: ").append(contact.getPersonName());
        sb.append("\nTitle: ").append(contact.getTitle());
        if (contact.getCompany() != null) {
            sb.append("\nCompany: ").append(contact.getCompany().getName());
        }

        sb.append("\n\nCandidate profile:");
        sb.append("\nName: ").append(profile.name());
        sb.append("\nTitle: ").append(profile.title());
        sb.append("\nYears experience: ").append(profile.yearsOfExperience());
        if (profile.skills() != null) {
            String skills = profile.skills().stream()
                    .filter(s -> "expert".equalsIgnoreCase(s.proficiency()) || "advanced".equalsIgnoreCase(s.proficiency()))
                    .map(PersonalProfile.ProfileSkill::name)
                    .limit(6)
                    .collect(Collectors.joining(", "));
            sb.append("\nTop skills: ").append(skills);
        }
        if (profile.preferences() != null && profile.preferences().locations() != null) {
            sb.append("\nTarget locations: ").append(String.join(", ", profile.preferences().locations()));
        }

        JobPosting job = input.targetJob();
        if (job != null) {
            sb.append("\n\nTheir open role:");
            sb.append("\nTitle: ").append(job.getTitle());
            if (job.getLocation() != null) {
                sb.append("\nLocation: ").append(job.getLocation());
            }
            if (job.getDescription() != null) {
                sb.append("\nKey requirements: ").append(truncate(job.getDescription(), 300));
            }
        }

        appendMessageHistory(sb, input);
        return sb.toString();
    }

    @Override
    public GeneratedMessage parseResponse(String raw, OutreachContext input) {
        String content = raw.strip();
        if (content.length() > 350) {
            content = content.substring(0, 347) + "...";
        }
        return new GeneratedMessage(
                content,
                MessageVariant.RECRUITER_PITCH,
                input.contact().getId(),
                input.targetJob() != null ? input.targetJob().getId() : null,
                null,
                0
        );
    }

    private void appendMessageHistory(StringBuilder sb, OutreachContext input) {
        if (input.messageHistory() != null && !input.messageHistory().isEmpty()) {
            sb.append("\n\nPrevious messages:");
            input.messageHistory().stream()
                    .limit(3)
                    .forEach(msg -> sb.append("\n- [")
                            .append(msg.getDirection())
                            .append("] ")
                            .append(truncate(msg.getContent(), 100)));
        }
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
