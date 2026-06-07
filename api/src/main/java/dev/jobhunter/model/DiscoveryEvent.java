package dev.jobhunter.model;

import dev.jobhunter.model.enums.DiscoveryOutcome;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "discovery_event")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiscoveryEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Company company;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(nullable = false)
    private String provider;

    @Column(name = "source_job_title")
    private String sourceJobTitle;

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "discovered_at", columnDefinition = "TIMESTAMP", nullable = false)
    private LocalDateTime discoveredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DiscoveryOutcome outcome;

    @PrePersist
    protected void onCreate() {
        if (discoveredAt == null) {
            discoveredAt = LocalDateTime.now();
        }
    }
}
