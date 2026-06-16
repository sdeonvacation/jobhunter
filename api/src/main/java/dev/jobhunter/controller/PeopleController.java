package dev.jobhunter.controller;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.people.dto.*;
import dev.jobhunter.people.model.ContactDiscoveryRun;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.RelationshipEvent;
import dev.jobhunter.people.model.enums.RelationshipStatus;
import dev.jobhunter.people.model.enums.Seniority;
import dev.jobhunter.people.repository.OutreachMessageRepository;
import dev.jobhunter.people.repository.RelationshipRepository;
import dev.jobhunter.people.service.ContactDiscoveryService;
import dev.jobhunter.people.service.RelationshipService;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class PeopleController {

    private final RelationshipService relationshipService;
    private final ContactDiscoveryService contactDiscoveryService;
    private final OutreachContactRepository outreachContactRepository;
    private final RelationshipRepository relationshipRepository;
    private final OutreachMessageRepository outreachMessageRepository;
    private final JobPostingRepository jobPostingRepository;

    public PeopleController(RelationshipService relationshipService,
                            ContactDiscoveryService contactDiscoveryService,
                            OutreachContactRepository outreachContactRepository,
                            RelationshipRepository relationshipRepository,
                            OutreachMessageRepository outreachMessageRepository,
                            JobPostingRepository jobPostingRepository) {
        this.relationshipService = relationshipService;
        this.contactDiscoveryService = contactDiscoveryService;
        this.outreachContactRepository = outreachContactRepository;
        this.relationshipRepository = relationshipRepository;
        this.outreachMessageRepository = outreachMessageRepository;
        this.jobPostingRepository = jobPostingRepository;
    }

    @GetMapping("/api/people")
    public Page<ContactDto> listContacts(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) String seniority,
            @RequestParam(defaultValue = "contactPriorityScore") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Sort sortOrder = switch (sort) {
            case "name" -> Sort.by(Sort.Direction.ASC, "personName");
            case "warmth" -> Sort.by(Sort.Direction.DESC, "warmthScore");
            case "recent" -> Sort.by(Sort.Direction.DESC, "lastContactedAt");
            default -> Sort.by(Sort.Direction.DESC, "contactPriorityScore");
        };

        Pageable pageable = PageRequest.of(page, size, sortOrder);
        Page<OutreachContact> contacts = outreachContactRepository.findAllOrderByPriorityDesc(pageable);

        return contacts.map(contact -> {
            Relationship relationship = relationshipRepository.findByContactId(contact.getId())
                    .orElse(null);
            return PeopleDtoMapper.toContactDto(contact, relationship);
        });
    }

    @GetMapping("/api/people/{id}")
    public ResponseEntity<ContactDetailDto> getContactDetail(@PathVariable UUID id) {
        return outreachContactRepository.findById(id)
                .map(contact -> {
                    Relationship relationship = relationshipRepository.findByContactId(id).orElse(null);
                    List<RelationshipEvent> events = relationship != null
                            ? relationshipService.getEvents(relationship.getId())
                            : List.of();
                    List<OutreachMessage> messages = outreachMessageRepository.findByContactIdOrderBySentAtDesc(id);
                    List<JobPosting> linkedJobs = jobPostingRepository.findByPosterContactId(id);

                    ContactDetailDto detail = PeopleDtoMapper.toContactDetailDto(
                            contact, relationship, events, messages, linkedJobs, null);
                    return ResponseEntity.ok(detail);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/people/{id}/events")
    public ResponseEntity<List<RelationshipEventDto>> getContactEvents(@PathVariable UUID id) {
        if (!outreachContactRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        Relationship relationship = relationshipRepository.findByContactId(id).orElse(null);
        if (relationship == null) {
            return ResponseEntity.ok(List.of());
        }
        List<RelationshipEvent> events = relationshipService.getEvents(relationship.getId());
        List<RelationshipEventDto> dtos = events.stream()
                .map(PeopleDtoMapper::toRelationshipEventDto)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/api/people/{id}/events")
    public ResponseEntity<RelationshipEventDto> recordEvent(
            @PathVariable UUID id,
            @RequestBody RecordEventRequest request) {
        if (!outreachContactRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        Relationship relationship = relationshipService.getOrCreate(id);
        RelationshipEvent event = relationshipService.recordEvent(
                relationship.getId(), request.eventType(), request.metadata());
        return ResponseEntity.ok(PeopleDtoMapper.toRelationshipEventDto(event));
    }

    @GetMapping("/api/people/stats")
    public PeopleStatsDto getStats() {
        long total = outreachContactRepository.count();

        Double avgPriority = outreachContactRepository.findAveragePriorityScore();

        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        long discoveredToday = outreachContactRepository.countDiscoveredSince(startOfToday);

        return new PeopleStatsDto(total, Map.of(), Map.of(), avgPriority != null ? avgPriority : 0.0, discoveredToday);
    }

    @PostMapping("/api/people/discover/{companyId}")
    public ResponseEntity<List<ContactDto>> triggerDiscovery(@PathVariable UUID companyId) {
        List<String> defaultKeywords = List.of("recruiter", "hiring manager", "talent acquisition", "engineering manager");
        List<OutreachContact> contacts = contactDiscoveryService.discoverForCompany(companyId, defaultKeywords);
        List<ContactDto> dtos = contacts.stream()
                .map(c -> PeopleDtoMapper.toContactDto(c, null))
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/api/people/discovery-runs")
    public List<ContactDiscoveryRunDto> getDiscoveryRuns(
            @RequestParam(required = false) String companyId) {
        if (companyId == null) {
            return List.of();
        }
        UUID companyFilter = UUID.fromString(companyId);
        List<ContactDiscoveryRun> runs = contactDiscoveryService.getDiscoveryRuns(companyFilter);
        return runs.stream()
                .map(PeopleDtoMapper::toContactDiscoveryRunDto)
                .toList();
    }

    @GetMapping("/api/companies/{id}/contacts")
    public List<ContactDto> getCompanyContacts(@PathVariable UUID id) {
        List<OutreachContact> contacts = outreachContactRepository.findByCompanyIdOrderByPriorityDesc(id);
        return contacts.stream()
                .map(contact -> {
                    Relationship relationship = relationshipRepository.findByContactId(contact.getId())
                            .orElse(null);
                    return PeopleDtoMapper.toContactDto(contact, relationship);
                })
                .toList();
    }
}
