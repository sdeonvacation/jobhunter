package dev.jobhunter.people.ai;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.RelationshipEvent;
import dev.jobhunter.service.PersonalProfile;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class ReferralAskTask implements AiTask<OutreachContext, GeneratedMessage> {

    @Override
    public String systemPrompt(OutreachContext input) {
        return """
                You are writing a LinkedIn message on behalf of a software engineer. \
                The goal is to ask for a referral to a specific role. Rules:
                - Be appreciative and direct
                - Acknowledge the existing relationship briefly
                - Explain why you're a good fit in one sentence
                - Make the ask clear and easy to act on
                - Keep it under 400 characters
                - No greeting like "Hi [Name]" — start with context directly
                - Don't be overly formal or sycophantic
                Output ONLY the message text, nothing else.""";
    }

    @Override
    public String userPrompt(OutreachContext input) {
        OutreachContact contact = input.contact();
        PersonalProfile profile = input.userProfile();
        Relationship relationship = input.relationship();

        StringBuilder sb = new StringBuilder();
        sb.append("Recipient: ").append(contact.getPersonName());
        sb.append("\nTitle: ").append(contact.getTitle());
        if (contact.getCompany() != null) {
            sb.append("\nCompany: ").append(contact.getCompany().getName());
        }

        sb.append("\n\nRelationship context:");
        if (relationship != null) {
            sb.append("\nStatus: ").append(relationship.getStatus());
            if (relationship.getLastContactAt() != null) {
                sb.append("\nLast contact: ").append(relationship.getLastContactAt().toLocalDate());
            }
            sb.append("\nResponse rate: ").append(String.format("%.0f%%", relationship.getResponseRate() * 100));
        }

        if (input.events() != null && !input.events().isEmpty()) {
            sb.append("\nRecent interactions:");
            input.events().stream()
                    .limit(3)
                    .forEach(e -> sb.append("\n- ").append(e.getEventType())
                            .append(" on ").append(e.getOccurredAt().toLocalDate()));
        }

        sb.append("\n\nSender profile:");
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

        JobPosting job = input.targetJob();
        if (job != null) {
            sb.append("\n\nTarget role:");
            sb.append("\nTitle: ").append(job.getTitle());
            if (job.getCompany() != null) {
                sb.append("\nCompany: ").append(job.getCompany().getName());
            }
            if (job.getLocation() != null) {
                sb.append("\nLocation: ").append(job.getLocation());
            }
        }

        appendMessageHistory(sb, input);
        return sb.toString();
    }

    @Override
    public GeneratedMessage parseResponse(String raw, OutreachContext input) {
        String content = raw.strip();
        if (content.length() > 400) {
            content = content.substring(0, 397) + "...";
        }
        return new GeneratedMessage(
                content,
                MessageVariant.REFERRAL_ASK,
                input.contact().getId(),
                input.targetJob() != null ? input.targetJob().getId() : null,
                null,
                0
        );
    }

    private void appendMessageHistory(StringBuilder sb, OutreachContext input) {
        if (input.messageHistory() != null && !input.messageHistory().isEmpty()) {
            sb.append("\n\nPrevious messages (most recent first):");
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
