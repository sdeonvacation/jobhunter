package dev.jobhunter.people.service;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.people.ai.MessageVariant;
import dev.jobhunter.people.ai.OutreachContext;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.RelationshipEvent;
import dev.jobhunter.people.repository.OutreachMessageRepository;
import dev.jobhunter.people.repository.RelationshipEventRepository;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Assembles full outreach context for AI message generation from multiple data sources.
 */
@Component
public class OutreachContextAssembler {

    private final RelationshipEventRepository eventRepository;
    private final OutreachMessageRepository messageRepository;
    private final PersonalProfileLoader profileLoader;

    public OutreachContextAssembler(RelationshipEventRepository eventRepository,
                                    OutreachMessageRepository messageRepository,
                                    PersonalProfileLoader profileLoader) {
        this.eventRepository = eventRepository;
        this.messageRepository = messageRepository;
        this.profileLoader = profileLoader;
    }

    public OutreachContext assemble(OutreachContact contact,
                                    Relationship relationship,
                                    JobPosting targetJob,
                                    MessageVariant variant) {
        List<RelationshipEvent> events = relationship != null
                ? eventRepository.findByRelationshipIdOrderByOccurredAtDesc(relationship.getId())
                : List.of();

        List<OutreachMessage> messageHistory = messageRepository
                .findByContactIdOrderBySentAtDesc(contact.getId());

        PersonalProfile profile = profileLoader.getProfile();

        return new OutreachContext(
                contact,
                relationship,
                events,
                messageHistory,
                targetJob,
                profile,
                variant
        );
    }
}
