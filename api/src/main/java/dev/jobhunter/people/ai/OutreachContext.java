package dev.jobhunter.people.ai;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.RelationshipEvent;
import dev.jobhunter.service.PersonalProfile;

import java.util.List;

public record OutreachContext(
        OutreachContact contact,
        Relationship relationship,
        List<RelationshipEvent> events,
        List<OutreachMessage> messageHistory,
        JobPosting targetJob,
        PersonalProfile userProfile,
        MessageVariant variant
) {
}
