package dev.jobhunter.people.ai;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.service.PersonalProfile;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class InfoChatTask implements AiTask<OutreachContext, GeneratedMessage> {

    @Override
    public String systemPrompt(OutreachContext input) {
        return """
                You are writing a LinkedIn message on behalf of a software engineer. \
                The goal is to start a genuine conversation by asking a specific, curious question about \
                the recipient's work or tech stack. Rules:
                - Be curious and specific, not generic
                - No compliments like "impressive background" or "great company"
                - Ask ONE question about something concrete in their profile
                - Keep it under 300 characters
                - No greeting like "Hi [Name]" — start with the question or context directly
                - Sound human, not templated
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
        if (contact.getLocation() != null) {
            sb.append("\nLocation: ").append(contact.getLocation());
        }

        sb.append("\n\nSender profile:");
        sb.append("\nName: ").append(profile.name());
        sb.append("\nTitle: ").append(profile.title());
        if (profile.skills() != null) {
            String skills = profile.skills().stream()
                    .map(PersonalProfile.ProfileSkill::name)
                    .limit(8)
                    .collect(Collectors.joining(", "));
            sb.append("\nKey skills: ").append(skills);
        }

        JobPosting job = input.targetJob();
        if (job != null) {
            sb.append("\n\nTarget job context:");
            sb.append("\nRole: ").append(job.getTitle());
            if (job.getCompany() != null) {
                sb.append("\nAt: ").append(job.getCompany().getName());
            }
        }

        appendMessageHistory(sb, input);
        return sb.toString();
    }

    @Override
    public GeneratedMessage parseResponse(String raw, OutreachContext input) {
        String content = raw.strip();
        if (content.length() > 300) {
            content = content.substring(0, 297) + "...";
        }
        return new GeneratedMessage(
                content,
                MessageVariant.INFO_CHAT,
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
