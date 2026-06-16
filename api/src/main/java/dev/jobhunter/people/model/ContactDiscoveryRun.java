package dev.jobhunter.people.model;

import dev.jobhunter.model.Company;
import dev.jobhunter.people.model.enums.ContactDiscoverySource;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "contact_discovery_run")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContactDiscoveryRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContactDiscoverySource source;

    @Column(name = "contacts_found", nullable = false)
    @Builder.Default
    private Integer contactsFound = 0;

    @Column(name = "contacts_new", nullable = false)
    @Builder.Default
    private Integer contactsNew = 0;

    @Column(name = "run_at", nullable = false)
    private LocalDateTime runAt;

    @PrePersist
    protected void onCreate() {
        if (runAt == null) {
            runAt = LocalDateTime.now();
        }
    }
}
