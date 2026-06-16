package dev.jobhunter.controller;

import dev.jobhunter.people.ai.GeneratedMessage;
import dev.jobhunter.people.ai.MessageVariant;
import dev.jobhunter.people.dto.*;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.people.model.enums.Channel;
import dev.jobhunter.people.model.enums.Direction;
import dev.jobhunter.people.model.enums.EventType;
import dev.jobhunter.people.model.enums.MessageType;
import dev.jobhunter.people.repository.OutreachMessageRepository;
import dev.jobhunter.people.service.OutreachMessageGenerator;
import dev.jobhunter.people.service.RelationshipService;
import dev.jobhunter.repository.OutreachContactRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
public class OutreachController {

    private final OutreachMessageGenerator messageGenerator;
    private final OutreachMessageRepository messageRepository;
    private final OutreachContactRepository contactRepository;
    private final RelationshipService relationshipService;

    public OutreachController(OutreachMessageGenerator messageGenerator,
                              OutreachMessageRepository messageRepository,
                              OutreachContactRepository contactRepository,
                              RelationshipService relationshipService) {
        this.messageGenerator = messageGenerator;
        this.messageRepository = messageRepository;
        this.contactRepository = contactRepository;
        this.relationshipService = relationshipService;
    }

    @PostMapping("/api/contacts/{id}/generate-message")
    public ResponseEntity<GeneratedMessageDto> generateMessage(
            @PathVariable UUID id,
            @RequestBody GenerateMessageRequest request) {

        if (!contactRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        MessageVariant variant;
        try {
            variant = MessageVariant.valueOf(request.variant().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        UUID jobId = request.jobId() != null && !request.jobId().isBlank()
                ? UUID.fromString(request.jobId())
                : null;

        GeneratedMessage generated = messageGenerator.generate(id, variant, jobId);

        GeneratedMessageDto dto = new GeneratedMessageDto(
                generated.content(),
                generated.variant().name(),
                generated.contactId().toString(),
                generated.jobId() != null ? generated.jobId().toString() : null,
                generated.modelUsed(),
                generated.tokensUsed()
        );
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/api/contacts/{id}/messages")
    public ResponseEntity<OutreachMessageDto> sendMessage(
            @PathVariable UUID id,
            @RequestBody SendMessageRequest request) {

        var contactOpt = contactRepository.findById(id);
        if (contactOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Channel channel;
        MessageType messageType;
        try {
            channel = Channel.valueOf(request.channel().toUpperCase());
            messageType = MessageType.valueOf(request.messageType().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        OutreachMessage message = OutreachMessage.builder()
                .contact(contactOpt.get())
                .direction(Direction.OUT)
                .channel(channel)
                .messageType(messageType)
                .content(request.content())
                .sentAt(LocalDateTime.now())
                .build();

        message = messageRepository.save(message);

        // Record MESSAGE_SENT event on relationship
        var relationship = relationshipService.getOrCreate(id);
        relationshipService.recordEvent(
                relationship.getId(),
                EventType.MESSAGE_SENT,
                Map.of("channel", channel.name(), "messageType", messageType.name())
        );

        log.debug("Recorded outreach message {} for contact {}", message.getId(), id);
        return ResponseEntity.ok(PeopleDtoMapper.toOutreachMessageDto(message));
    }

    @GetMapping("/api/contacts/{id}/messages")
    public ResponseEntity<List<OutreachMessageDto>> getMessages(@PathVariable UUID id) {
        if (!contactRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        List<OutreachMessage> messages = messageRepository.findByContactIdOrderBySentAtDesc(id);
        List<OutreachMessageDto> dtos = messages.stream()
                .map(PeopleDtoMapper::toOutreachMessageDto)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/api/contacts/{id}/available-variants")
    public ResponseEntity<List<String>> getAvailableVariants(@PathVariable UUID id) {
        if (!contactRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        List<MessageVariant> variants = messageGenerator.getAvailableVariants(id);
        return ResponseEntity.ok(variants.stream().map(MessageVariant::name).toList());
    }
}
