package dev.jobhunter.people.ai;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.service.PersonalProfile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class TechDiscussionTask implements AiTask<OutreachContext, GeneratedMessage> {

    @Override
    public String systemPrompt(OutreachContext input) {
        return """
                You are writing a LinkedIn message on behalf of a software engineer. \
                The goal is to start a peer-to-peer technical discussion. Rules:
                - Be technically specific — reference a shared technology or approach
                - Peer tone, not fanboy or hierarchical
                - Mention a concrete technical topic, pattern, or challenge
                - Keep it under 350 characters
                - No greeting like "Hi [Name]" — start with the technical hook directly
                - Sound like a fellow engineer, not a recruiter
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

        sb.append("\n\nSender profile:");
        sb.append("\nName: ").append(profile.name());
        sb.append("\nTitle: ").append(profile.title());
        if (profile.skills() != null) {
            String skills = profile.skills().stream()
                    .map(PersonalProfile.ProfileSkill::name)
                    .limit(10)
                    .collect(Collectors.joining(", "));
            sb.append("\nSkills: ").append(skills);
        }

        // Find shared tech stack for context
        if (contact.getTechStack() != null && profile.skills() != null) {
            List<String> contactTech = contact.getTechStack().stream()
                    .map(String::toLowerCase)
                    .toList();
            String shared = profile.skills().stream()
                    .map(PersonalProfile.ProfileSkill::name)
                    .filter(s -> contactTech.contains(s.toLowerCase()))
                    .collect(Collectors.joining(", "));
            if (!shared.isEmpty()) {
                sb.append("\nShared technologies: ").append(shared);
            }
        }

        JobPosting job = input.targetJob();
        if (job != null) {
            sb.append("\n\nRelevant job posting:");
            sb.append("\nRole: ").append(job.getTitle());
            if (job.getDescription() != null) {
                sb.append("\nTech mentioned: ").append(truncate(job.getDescription(), 200));
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
                MessageVariant.TECH_DISCUSSION,
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
