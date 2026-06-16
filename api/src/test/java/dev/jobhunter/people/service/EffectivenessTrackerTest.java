package dev.jobhunter.people.service;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.people.model.enums.Channel;
import dev.jobhunter.people.model.enums.Direction;
import dev.jobhunter.people.model.enums.MessageType;
import dev.jobhunter.people.repository.OutreachMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EffectivenessTrackerTest {

    @Mock
    private OutreachMessageRepository outreachMessageRepository;

    private EffectivenessTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new EffectivenessTracker(outreachMessageRepository);
    }

    @Test
    void getVariantEffectiveness_noMessages_returnsEmptyMap() {
        when(outreachMessageRepository.findAll()).thenReturn(List.of());

        Map<String, EffectivenessTracker.EffectivenessMetrics> result =
                tracker.getVariantEffectiveness(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        assertThat(result).isEmpty();
    }

    @Test
    void getVariantEffectiveness_groupsByTemplate() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID()).personName("Test").linkedinUrl("http://li.com/test").build();

        OutreachMessage msg1 = OutreachMessage.builder()
                .id(UUID.randomUUID()).contact(contact).direction(Direction.OUT)
                .channel(Channel.LINKEDIN).messageType(MessageType.INFO_CHAT)
                .sentAt(LocalDateTime.of(2024, 6, 1, 10, 0))
                .templateUsed("template_a").replied(false).build();
        OutreachMessage msg2 = OutreachMessage.builder()
                .id(UUID.randomUUID()).contact(contact).direction(Direction.OUT)
                .channel(Channel.LINKEDIN).messageType(MessageType.REFERRAL)
                .sentAt(LocalDateTime.of(2024, 6, 5, 10, 0))
                .templateUsed("template_b").replied(true).build();

        when(outreachMessageRepository.findAll()).thenReturn(List.of(msg1, msg2));

        Map<String, EffectivenessTracker.EffectivenessMetrics> result =
                tracker.getVariantEffectiveness(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        assertThat(result).hasSize(2);
        assertThat(result.get("template_a").totalSent()).isEqualTo(1);
        assertThat(result.get("template_a").replies()).isZero();
        assertThat(result.get("template_b").totalSent()).isEqualTo(1);
        assertThat(result.get("template_b").replies()).isEqualTo(1);
    }

    @Test
    void getVariantEffectiveness_nullTemplateGroupedUnderNoTemplate() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID()).personName("Test").linkedinUrl("http://li.com/test").build();

        OutreachMessage msg = OutreachMessage.builder()
                .id(UUID.randomUUID()).contact(contact).direction(Direction.OUT)
                .channel(Channel.LINKEDIN).messageType(MessageType.FOLLOWUP)
                .sentAt(LocalDateTime.of(2024, 4, 1, 12, 0))
                .templateUsed(null).replied(false).build();

        when(outreachMessageRepository.findAll()).thenReturn(List.of(msg));

        Map<String, EffectivenessTracker.EffectivenessMetrics> result =
                tracker.getVariantEffectiveness(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        assertThat(result).containsKey("no_template");
        assertThat(result.get("no_template").totalSent()).isEqualTo(1);
    }

    @Test
    void getVariantEffectiveness_filtersOutInboundMessages() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID()).personName("Test").linkedinUrl("http://li.com/test").build();

        OutreachMessage inbound = OutreachMessage.builder()
                .id(UUID.randomUUID()).contact(contact).direction(Direction.IN)
                .channel(Channel.LINKEDIN).messageType(MessageType.INFO_CHAT)
                .sentAt(LocalDateTime.of(2024, 5, 1, 10, 0))
                .templateUsed("template_x").replied(false).build();

        when(outreachMessageRepository.findAll()).thenReturn(List.of(inbound));

        Map<String, EffectivenessTracker.EffectivenessMetrics> result =
                tracker.getVariantEffectiveness(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        assertThat(result).isEmpty();
    }

    @Test
    void getVariantEffectiveness_filtersOutOfDateRange() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID()).personName("Test").linkedinUrl("http://li.com/test").build();

        OutreachMessage beforeRange = OutreachMessage.builder()
                .id(UUID.randomUUID()).contact(contact).direction(Direction.OUT)
                .channel(Channel.LINKEDIN).messageType(MessageType.INFO_CHAT)
                .sentAt(LocalDateTime.of(2023, 12, 31, 23, 59))
                .templateUsed("old_template").replied(true).build();

        when(outreachMessageRepository.findAll()).thenReturn(List.of(beforeRange));

        Map<String, EffectivenessTracker.EffectivenessMetrics> result =
                tracker.getVariantEffectiveness(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        assertThat(result).isEmpty();
    }

    @Test
    void getVariantEffectiveness_replyRateCalculation() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID()).personName("Test").linkedinUrl("http://li.com/test").build();

        OutreachMessage msg1 = OutreachMessage.builder()
                .id(UUID.randomUUID()).contact(contact).direction(Direction.OUT)
                .channel(Channel.LINKEDIN).messageType(MessageType.INFO_CHAT)
                .sentAt(LocalDateTime.of(2024, 6, 1, 10, 0))
                .templateUsed("variant_a").replied(true).build();
        OutreachMessage msg2 = OutreachMessage.builder()
                .id(UUID.randomUUID()).contact(contact).direction(Direction.OUT)
                .channel(Channel.LINKEDIN).messageType(MessageType.INFO_CHAT)
                .sentAt(LocalDateTime.of(2024, 6, 2, 10, 0))
                .templateUsed("variant_a").replied(false).build();
        OutreachMessage msg3 = OutreachMessage.builder()
                .id(UUID.randomUUID()).contact(contact).direction(Direction.OUT)
                .channel(Channel.LINKEDIN).messageType(MessageType.INFO_CHAT)
                .sentAt(LocalDateTime.of(2024, 6, 3, 10, 0))
                .templateUsed("variant_a").replied(true).build();

        when(outreachMessageRepository.findAll()).thenReturn(List.of(msg1, msg2, msg3));

        Map<String, EffectivenessTracker.EffectivenessMetrics> result =
                tracker.getVariantEffectiveness(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        EffectivenessTracker.EffectivenessMetrics metrics = result.get("variant_a");
        assertThat(metrics.totalSent()).isEqualTo(3);
        assertThat(metrics.replies()).isEqualTo(2);
        assertThat(metrics.replyRate()).isEqualTo(0.667);
        assertThat(metrics.sampleSize()).isEqualTo(3);
    }

    @Test
    void getVariantEffectiveness_interviewConversionTracking() {
        OutreachContact contact = OutreachContact.builder()
                .id(UUID.randomUUID()).personName("Test").linkedinUrl("http://li.com/test").build();

        OutreachMessage referralReply = OutreachMessage.builder()
                .id(UUID.randomUUID()).contact(contact).direction(Direction.OUT)
                .channel(Channel.LINKEDIN).messageType(MessageType.REFERRAL)
                .sentAt(LocalDateTime.of(2024, 6, 1, 10, 0))
                .templateUsed("referral_v1").replied(true).build();
        OutreachMessage recruiterReply = OutreachMessage.builder()
                .id(UUID.randomUUID()).contact(contact).direction(Direction.OUT)
                .channel(Channel.LINKEDIN).messageType(MessageType.RECRUITER)
                .sentAt(LocalDateTime.of(2024, 6, 2, 10, 0))
                .templateUsed("referral_v1").replied(true).build();
        OutreachMessage chatNoReply = OutreachMessage.builder()
                .id(UUID.randomUUID()).contact(contact).direction(Direction.OUT)
                .channel(Channel.LINKEDIN).messageType(MessageType.INFO_CHAT)
                .sentAt(LocalDateTime.of(2024, 6, 3, 10, 0))
                .templateUsed("referral_v1").replied(true).build();

        when(outreachMessageRepository.findAll()).thenReturn(List.of(referralReply, recruiterReply, chatNoReply));

        Map<String, EffectivenessTracker.EffectivenessMetrics> result =
                tracker.getVariantEffectiveness(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        EffectivenessTracker.EffectivenessMetrics metrics = result.get("referral_v1");
        assertThat(metrics.interviewsGenerated()).isEqualTo(2); // REFERRAL + RECRUITER
        assertThat(metrics.interviewConversionRate()).isEqualTo(0.667);
    }
}
