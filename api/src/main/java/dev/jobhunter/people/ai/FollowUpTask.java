package dev.jobhunter.people.ai;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.service.PersonalProfile;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class FollowUpTask implements AiTask<OutreachContext, GeneratedMessage> {

    @Override
    public String systemPrompt(OutreachContext input) {
        return """
                You are writing a follow-up LinkedIn message on behalf of a software engineer. \
                The goal is to re-engage after a previous interaction. Rules:
                - Be brief — this is a follow-up, not a new intro
                - Add new value: share an insight, article reference, or relevant update
                - Reference the previous interaction naturally
                - Keep it under 250 characters
                - No greeting like "Hi [Name]" — jump straight in
                - Don't be pushy or apologetic about following up
                Output ONLY the message text, nothing else.""";
    }

    @Override
    public String userPrompt(OutreachContext input) {
        OutreachContact contact = input.contact();
        PersonalProfile profile = input.userProfile();

        StringBuilder sb = new StringBuilder();
        sb.append("Recipient: ").append(contact.getPersonName());
        sb.append("\nTitle: ").append(contact.getTitle());
        if (contact.getCompany() != null) {
            sb.append("\nCompany: ").append(contact.getCompany().getName());
        }
        if (contact.getTechStack() != null && !contact.getTechStack().isEmpty()) {
            sb.append("\nTheir tech stack: ").append(String.join(", ", contact.getTechStack()));
        }

        sb.append("\n\nSender: ").append(profile.name());
        sb.append(" (").append(profile.title()).append(")");

        // Previous interaction context is critical for follow-ups
        if (input.messageHistory() != null && !input.messageHistory().isEmpty()) {
            sb.append("\n\nConversation history (most recent first):");
            input.messageHistory().stream()
                    .limit(5)
                    .forEach(msg -> sb.append("\n- [")
                            .append(msg.getDirection())
                            .append("] ")
                            .append(truncate(msg.getContent(), 150)));
        }

        if (input.events() != null && !input.events().isEmpty()) {
            sb.append("\n\nRecent events:");
            input.events().stream()
                    .limit(3)
                    .forEach(e -> sb.append("\n- ").append(e.getEventType())
                            .append(" on ").append(e.getOccurredAt().toLocalDate()));
        }

        if (input.targetJob() != null) {
            sb.append("\n\nRelevant role: ").append(input.targetJob().getTitle());
            if (input.targetJob().getCompany() != null) {
                sb.append(" at ").append(input.targetJob().getCompany().getName());
            }
        }

        return sb.toString();
    }

    @Override
    public GeneratedMessage parseResponse(String raw, OutreachContext input) {
        String content = raw.strip();
        if (content.length() > 250) {
            content = content.substring(0, 247) + "...";
        }
        return new GeneratedMessage(
                content,
                MessageVariant.FOLLOW_UP,
                input.contact().getId(),
                input.targetJob() != null ? input.targetJob().getId() : null,
                null,
                0
        );
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
