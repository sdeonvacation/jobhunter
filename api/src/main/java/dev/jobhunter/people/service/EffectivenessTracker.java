package dev.jobhunter.people.service;

import dev.jobhunter.people.model.OutreachMessage;
import dev.jobhunter.people.model.enums.Direction;
import dev.jobhunter.people.repository.OutreachMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class EffectivenessTracker {

    private final OutreachMessageRepository outreachMessageRepository;

    public EffectivenessTracker(OutreachMessageRepository outreachMessageRepository) {
        this.outreachMessageRepository = outreachMessageRepository;
    }

    public Map<String, EffectivenessMetrics> getVariantEffectiveness(LocalDate from, LocalDate to) {
        LocalDateTime fromDateTime = from.atStartOfDay();
        LocalDateTime toDateTime = to.atTime(LocalTime.MAX);

        List<OutreachMessage> allMessages = outreachMessageRepository.findAll().stream()
                .filter(m -> m.getDirection() == Direction.OUT)
                .filter(m -> m.getSentAt() != null)
                .filter(m -> !m.getSentAt().isBefore(fromDateTime) && !m.getSentAt().isAfter(toDateTime))
                .toList();

        Map<String, List<OutreachMessage>> grouped = allMessages.stream()
                .collect(Collectors.groupingBy(m -> m.getTemplateUsed() != null ? m.getTemplateUsed() : "no_template"));

        Map<String, EffectivenessMetrics> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<OutreachMessage>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), computeMetrics(entry.getValue()));
        }
        return result;
    }

    private EffectivenessMetrics computeMetrics(List<OutreachMessage> messages) {
        int totalSent = messages.size();
        int replies = (int) messages.stream()
                .filter(m -> Boolean.TRUE.equals(m.getReplied()))
                .count();
        double replyRate = totalSent > 0 ? Math.round((double) replies / totalSent * 1000.0) / 1000.0 : 0.0;

        // Interview conversion: count messages where contact eventually got an interview
        // Approximation: messages with replied=true and messageType is REFERRAL or RECRUITER
        int interviewsGenerated = (int) messages.stream()
                .filter(m -> Boolean.TRUE.equals(m.getReplied()))
                .filter(m -> m.getMessageType() != null && isInterviewSignal(m))
                .count();

        double interviewConversionRate = totalSent > 0
                ? Math.round((double) interviewsGenerated / totalSent * 1000.0) / 1000.0
                : 0.0;

        return new EffectivenessMetrics(totalSent, replies, replyRate, interviewsGenerated, interviewConversionRate, totalSent);
    }

    private boolean isInterviewSignal(OutreachMessage message) {
        return switch (message.getMessageType()) {
            case REFERRAL, RECRUITER -> true;
            default -> false;
        };
    }

    public Map<String, EffectivenessMetrics> getChannelEffectiveness(LocalDate from, LocalDate to) {
        LocalDateTime fromDateTime = from.atStartOfDay();
        LocalDateTime toDateTime = to.atTime(LocalTime.MAX);

        List<OutreachMessage> allMessages = outreachMessageRepository.findAll().stream()
                .filter(m -> m.getDirection() == Direction.OUT)
                .filter(m -> m.getSentAt() != null)
                .filter(m -> !m.getSentAt().isBefore(fromDateTime) && !m.getSentAt().isAfter(toDateTime))
                .toList();

        Map<String, List<OutreachMessage>> grouped = allMessages.stream()
                .collect(Collectors.groupingBy(m -> m.getChannel() != null ? m.getChannel().name() : "UNKNOWN"));

        Map<String, EffectivenessMetrics> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<OutreachMessage>> entry : grouped.entrySet()) {
            result.put(entry.getKey(), computeMetrics(entry.getValue()));
        }
        return result;
    }

    public record EffectivenessMetrics(
            int totalSent,
            int replies,
            double replyRate,
            int interviewsGenerated,
            double interviewConversionRate,
            int sampleSize
    ) {}
}
