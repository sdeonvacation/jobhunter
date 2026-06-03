package dev.jobhub.model;

import dev.jobhub.model.enums.OutcomeStage;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "job_outcome")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Application application;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutcomeStage stage;

    @Column(name = "occurred_at")
    private LocalDate occurredAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
