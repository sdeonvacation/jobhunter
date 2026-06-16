package dev.jobhunter.people.service;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.people.ai.AiTask;
import dev.jobhunter.people.ai.GeneratedMessage;
import dev.jobhunter.people.ai.MessageVariant;
import dev.jobhunter.people.ai.OutreachContext;
import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.enums.RelationshipStatus;
import dev.jobhunter.people.repository.RelationshipRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import dev.jobhunter.service.PersonalProfileLoader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generates outreach messages using AI tasks selected by message variant.
 */
@Slf4j
@Service
public class OutreachMessageGenerator {

    private final OutreachContextAssembler contextAssembler;
    private final AiProvider aiProvider;
    private final OutreachContactRepository contactRepository;
    private final RelationshipRepository relationshipRepository;
    private final JobPostingRepository jobPostingRepository;
    private final Map<MessageVariant, AiTask<OutreachContext, GeneratedMessage>> tasksByVariant;

    @SuppressWarnings("unchecked")
    public OutreachMessageGenerator(OutreachContextAssembler contextAssembler,
                                    AiProvider aiProvider,
                                    OutreachContactRepository contactRepository,
                                    RelationshipRepository relationshipRepository,
                                    JobPostingRepository jobPostingRepository,
                                    List<AiTask<OutreachContext, GeneratedMessage>> tasks) {
        this.contextAssembler = contextAssembler;
        this.aiProvider = aiProvider;
        this.contactRepository = contactRepository;
        this.relationshipRepository = relationshipRepository;
        this.jobPostingRepository = jobPostingRepository;
        // Filter out non-outreach AiTask impls (e.g., FunnelAnalysisTask) that get injected due to type erasure
        this.tasksByVariant = tasks.stream()
                .filter(t -> resolveVariantSafe(t) != null)
                .collect(Collectors.toMap(this::resolveVariant, Function.identity()));
        log.info("Registered {} outreach AI tasks: {}", tasksByVariant.size(), tasksByVariant.keySet());
    }

    public GeneratedMessage generate(UUID contactId, MessageVariant variant, UUID jobId) {
        OutreachContact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + contactId));

        Relationship relationship = relationshipRepository.findByContactId(contactId).orElse(null);

        JobPosting targetJob = null;
        if (jobId != null) {
            targetJob = jobPostingRepository.findById(jobId).orElse(null);
        }

        AiTask<OutreachContext, GeneratedMessage> task = tasksByVariant.get(variant);
        if (task == null) {
            throw new IllegalArgumentException("No AI task registered for variant: " + variant);
        }

        OutreachContext context = contextAssembler.assemble(contact, relationship, targetJob, variant);

        String systemPrompt = task.systemPrompt(context);
        String userPrompt = task.userPrompt(context);

        String rawResponse = aiProvider.generate(systemPrompt, userPrompt);

        GeneratedMessage result = task.parseResponse(rawResponse, context);
        // Enrich with model info
        return new GeneratedMessage(
                result.content(),
                result.variant(),
                result.contactId(),
                result.jobId(),
                aiProvider.name(),
                rawResponse.length() / 4 // approximate token count
        );
    }

    public List<MessageVariant> getAvailableVariants(UUID contactId) {
        Relationship relationship = relationshipRepository.findByContactId(contactId).orElse(null);
        RelationshipStatus status = relationship != null
                ? relationship.getStatus()
                : RelationshipStatus.DISCOVERED;

        return switch (status) {
            case DISCOVERED -> List.of(
                    MessageVariant.INFO_CHAT,
                    MessageVariant.TECH_DISCUSSION,
                    MessageVariant.RECRUITER_PITCH
            );
            case CONTACTED, REPLIED -> List.of(
                    MessageVariant.FOLLOW_UP,
                    MessageVariant.TECH_DISCUSSION,
                    MessageVariant.REFERRAL_ASK
            );
            case ENGAGED, REFERRED, INTERVIEW_OBTAINED -> List.of(
                    MessageVariant.REFERRAL_ASK,
                    MessageVariant.FOLLOW_UP
            );
            case GHOSTED, COLD -> List.of(
                    MessageVariant.FOLLOW_UP
            );
        };
    }

    private MessageVariant resolveVariant(AiTask<OutreachContext, GeneratedMessage> task) {
        MessageVariant v = resolveVariantSafe(task);
        return v != null ? v : MessageVariant.INFO_CHAT;
    }

    private MessageVariant resolveVariantSafe(AiTask<OutreachContext, GeneratedMessage> task) {
        String className = task.getClass().getSimpleName().replace("Task", "");
        String enumName = camelToSnakeUpper(className);
        try {
            return MessageVariant.valueOf(enumName);
        } catch (IllegalArgumentException e) {
            return null; // Not an outreach task
        }
    }

    private String camelToSnakeUpper(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase();
    }
}
