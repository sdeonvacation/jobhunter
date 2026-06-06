package dev.jobhub.linkedin;

import dev.jobhub.model.Company;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "outreach_contact")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutreachContact {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Company company;

    @Column(name = "linkedin_url", nullable = false, unique = true)
    private String linkedinUrl;

    @Column(name = "person_name", nullable = false)
    private String personName;

    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_status", nullable = false)
    @Builder.Default
    private ConnectionStatus connectionStatus = ConnectionStatus.NONE;

    @Column(name = "last_contacted_at")
    private LocalDateTime lastContactedAt;

    @Column(name = "connection_sent_at")
    private LocalDateTime connectionSentAt;

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
