package dev.jobhunter.controller;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.people.ai.GeneratedMessage;
import dev.jobhunter.people.ai.MessageVariant;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.people.model.Relationship;
import dev.jobhunter.people.model.RelationshipEvent;
import dev.jobhunter.people.model.enums.Channel;
import dev.jobhunter.people.model.enums.Direction;
import dev.jobhunter.people.model.enums.EventType;
import dev.jobhunter.people.model.enums.MessageType;
import dev.jobhunter.people.model.enums.RelationshipStatus;
import dev.jobhunter.people.repository.OutreachMessageRepository;
import dev.jobhunter.people.service.OutreachMessageGenerator;
import dev.jobhunter.people.service.RelationshipService;
import dev.jobhunter.repository.OutreachContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutreachControllerTest {

    @Mock
    private OutreachMessageGenerator messageGenerator;
    @Mock
    private OutreachMessageRepository messageRepository;
    @Mock
    private OutreachContactRepository contactRepository;
    @Mock
    private RelationshipService relationshipService;

    private OutreachController controller;

    @BeforeEach
    void setUp() {
        controller = new OutreachController(
                messageGenerator, messageRepository, contactRepository, relationshipService);
    }

    @Test
    void generateMessage_contactNotFound_returns404() {
        UUID id = UUID.randomUUID();
        when(contactRepository.existsById(id)).thenReturn(false);

        var request = new dev.jobhunter.people.dto.GenerateMessageRequest("INFO_CHAT", null);
        var response = controller.generateMessage(id, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void generateMessage_invalidVariant_returns400() {
        UUID id = UUID.randomUUID();
        when(contactRepository.existsById(id)).thenReturn(true);

        var request = new dev.jobhunter.people.dto.GenerateMessageRequest("INVALID_VARIANT", null);
        var response = controller.generateMessage(id, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void generateMessage_success_returnsDto() {
        UUID contactId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(contactRepository.existsById(contactId)).thenReturn(true);

        GeneratedMessage generated = new GeneratedMessage(
                "Hi, curious about your microservices migration...",
                MessageVariant.INFO_CHAT,
                contactId,
                jobId,
                "openai",
                42
        );
        when(messageGenerator.generate(contactId, MessageVariant.INFO_CHAT, jobId))
                .thenReturn(generated);

        var request = new dev.jobhunter.people.dto.GenerateMessageRequest("INFO_CHAT", jobId.toString());
        var response = controller.generateMessage(contactId, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.content()).isEqualTo("Hi, curious about your microservices migration...");
        assertThat(dto.variant()).isEqualTo("INFO_CHAT");
        assertThat(dto.contactId()).isEqualTo(contactId.toString());
        assertThat(dto.jobId()).isEqualTo(jobId.toString());
        assertThat(dto.modelUsed()).isEqualTo("openai");
        assertThat(dto.tokensUsed()).isEqualTo(42);
    }

    @Test
    void sendMessage_contactNotFound_returns404() {
        UUID id = UUID.randomUUID();
        when(contactRepository.findById(id)).thenReturn(Optional.empty());

        var request = new dev.jobhunter.people.dto.SendMessageRequest("Hello", "LINKEDIN", "INFO_CHAT");
        var response = controller.sendMessage(id, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void sendMessage_invalidChannel_returns400() {
        UUID id = UUID.randomUUID();
        OutreachContact contact = OutreachContact.builder()
                .id(id)
                .personName("Test")
                .linkedinUrl("https://linkedin.com/in/test")
                .build();
        when(contactRepository.findById(id)).thenReturn(Optional.of(contact));

        var request = new dev.jobhunter.people.dto.SendMessageRequest("Hello", "INVALID", "INFO_CHAT");
        var response = controller.sendMessage(id, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sendMessage_invalidMessageType_returns400() {
        UUID id = UUID.randomUUID();
        OutreachContact contact = OutreachContact.builder()
                .id(id)
                .personName("Test")
                .linkedinUrl("https://linkedin.com/in/test")
                .build();
        when(contactRepository.findById(id)).thenReturn(Optional.of(contact));

        var request = new dev.jobhunter.people.dto.SendMessageRequest("Hello", "LINKEDIN", "INVALID_TYPE");
        var response = controller.sendMessage(id, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sendMessage_success_savesMessageAndRecordsEvent() {
        UUID contactId = UUID.randomUUID();
        UUID relationshipId = UUID.randomUUID();

        OutreachContact contact = OutreachContact.builder()
                .id(contactId)
                .personName("Test Contact")
                .linkedinUrl("https://linkedin.com/in/test")
                .build();

        Relationship relationship = Relationship.builder()
                .id(relationshipId)
                .contact(contact)
                .status(RelationshipStatus.DISCOVERED)
                .build();

        OutreachMessage savedMessage = OutreachMessage.builder()
                .id(UUID.randomUUID())
                .contact(contact)
                .direction(Direction.OUT)
                .channel(Channel.LINKEDIN)
                .messageType(MessageType.INFO_CHAT)
                .content("Hello there!")
                .sentAt(LocalDateTime.now())
                .build();

        when(contactRepository.findById(contactId)).thenReturn(Optional.of(contact));
        when(messageRepository.save(any(OutreachMessage.class))).thenReturn(savedMessage);
        when(relationshipService.getOrCreate(contactId)).thenReturn(relationship);
        when(relationshipService.recordEvent(eq(relationshipId), eq(EventType.MESSAGE_SENT), any()))
                .thenReturn(RelationshipEvent.builder().id(UUID.randomUUID()).build());

        var request = new dev.jobhunter.people.dto.SendMessageRequest("Hello there!", "LINKEDIN", "INFO_CHAT");
        var response = controller.sendMessage(contactId, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.content()).isEqualTo("Hello there!");
        assertThat(dto.channel()).isEqualTo(Channel.LINKEDIN);
        assertThat(dto.messageType()).isEqualTo(MessageType.INFO_CHAT);
        assertThat(dto.direction()).isEqualTo(Direction.OUT);

        // Verify message was saved
        ArgumentCaptor<OutreachMessage> msgCaptor = ArgumentCaptor.forClass(OutreachMessage.class);
        verify(messageRepository).save(msgCaptor.capture());
        OutreachMessage captured = msgCaptor.getValue();
        assertThat(captured.getDirection()).isEqualTo(Direction.OUT);
        assertThat(captured.getChannel()).isEqualTo(Channel.LINKEDIN);
        assertThat(captured.getContent()).isEqualTo("Hello there!");

        // Verify event was recorded
        verify(relationshipService).recordEvent(eq(relationshipId), eq(EventType.MESSAGE_SENT), any());
    }

    @Test
    void getMessages_contactNotFound_returns404() {
        UUID id = UUID.randomUUID();
        when(contactRepository.existsById(id)).thenReturn(false);

        var response = controller.getMessages(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getMessages_success_returnsList() {
        UUID contactId = UUID.randomUUID();
        when(contactRepository.existsById(contactId)).thenReturn(true);

        OutreachMessage msg = OutreachMessage.builder()
                .id(UUID.randomUUID())
                .direction(Direction.OUT)
                .channel(Channel.LINKEDIN)
                .messageType(MessageType.INFO_CHAT)
                .content("Test message")
                .sentAt(LocalDateTime.now())
                .replied(false)
                .build();

        when(messageRepository.findByContactIdOrderBySentAtDesc(contactId))
                .thenReturn(List.of(msg));

        var response = controller.getMessages(contactId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).content()).isEqualTo("Test message");
    }

    @Test
    void getAvailableVariants_contactNotFound_returns404() {
        UUID id = UUID.randomUUID();
        when(contactRepository.existsById(id)).thenReturn(false);

        var response = controller.getAvailableVariants(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getAvailableVariants_success_returnsVariantNames() {
        UUID contactId = UUID.randomUUID();
        when(contactRepository.existsById(contactId)).thenReturn(true);
        when(messageGenerator.getAvailableVariants(contactId))
                .thenReturn(List.of(MessageVariant.INFO_CHAT, MessageVariant.TECH_DISCUSSION));

        var response = controller.getAvailableVariants(contactId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly("INFO_CHAT", "TECH_DISCUSSION");
    }
}
