package dev.jobhunter.linkedin;

import dev.jobhunter.model.Company;
import dev.jobhunter.people.model.enums.ContactDiscoverySource;
import dev.jobhunter.people.model.enums.Seniority;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "seniority")
    private Seniority seniority;

    @Enumerated(EnumType.STRING)
    @Column(name = "discovered_via")
    @Builder.Default
    private ContactDiscoverySource discoveredVia = ContactDiscoverySource.MANUAL;

    @Column(name = "location")
    private String location;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tech_stack", columnDefinition = "jsonb")
    private List<String> techStack;

    @Column(name = "interview_generation_weight")
    @Builder.Default
    private Integer interviewGenerationWeight = 0;

    @Column(name = "warmth_score")
    @Builder.Default
    private Integer warmthScore = 0;

    @Column(name = "contact_priority_score")
    @Builder.Default
    private Integer contactPriorityScore = 0;

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
