package dev.jobhunter.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "aggregator_run")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AggregatorRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_name", nullable = false, unique = true)
    private String sourceName;

    @Column(name = "last_run_at", nullable = false)
    private LocalDateTime lastRunAt;

    @Column(name = "last_status", nullable = false)
    private String lastStatus;

    @Column(name = "jobs_fetched")
    @Builder.Default
    private int jobsFetched = 0;

    @Column(name = "jobs_created")
    @Builder.Default
    private int jobsCreated = 0;

    @Column(name = "jobs_enriched")
    @Builder.Default
    private int jobsEnriched = 0;

    @Column(name = "jobs_filtered")
    @Builder.Default
    private int jobsFiltered = 0;

    @Builder.Default
    private int errors = 0;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "elapsed_ms")
    @Builder.Default
    private long elapsedMs = 0;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
