package dev.jobhunter.people.model;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.people.model.enums.Channel;
import dev.jobhunter.people.model.enums.Direction;
import dev.jobhunter.people.model.enums.MessageType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "outreach_message")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutreachMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private OutreachContact contact;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false)
    private MessageType messageType;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(name = "replied")
    @Builder.Default
    private Boolean replied = false;

    @Column(name = "replied_at")
    private LocalDateTime repliedAt;

    @Column(name = "template_used")
    private String templateUsed;

    @Column(name = "ai_generated")
    @Builder.Default
    private boolean aiGenerated = false;

    @Column(name = "tokens_used")
    @Builder.Default
    private int tokensUsed = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (sentAt == null) {
            sentAt = LocalDateTime.now();
        }
    }
}
