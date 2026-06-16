package dev.jobhunter.people.model;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.people.model.enums.RelationshipStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "relationship")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Relationship {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contact_id", nullable = false, unique = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private OutreachContact contact;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RelationshipStatus status = RelationshipStatus.DISCOVERED;

    @Column(name = "last_contact_at")
    private LocalDateTime lastContactAt;

    @Column(name = "last_reply_at")
    private LocalDateTime lastReplyAt;

    @Column(name = "response_rate")
    @Builder.Default
    private Double responseRate = 0.0;

    @Column(name = "referral_requested")
    @Builder.Default
    private Boolean referralRequested = false;

    @Column(name = "referred")
    @Builder.Default
    private Boolean referred = false;

    @Column(name = "interview_obtained")
    @Builder.Default
    private Boolean interviewObtained = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referred_by_contact_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private OutreachContact referredByContact;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
