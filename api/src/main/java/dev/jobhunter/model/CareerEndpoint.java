package dev.jobhunter.model;

import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.Confidence;
import dev.jobhunter.model.enums.CrawlStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "career_endpoint")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CareerEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Company company;

    @Column(nullable = false)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "ats_type", nullable = false)
    private AtsType atsType;

    @Column(name = "ats_slug")
    private String atsSlug;

    @Column(name = "ats_shard_id")
    private String atsShardId;

    @Enumerated(EnumType.STRING)
    private Confidence confidence;

    @Column(nullable = false)
    @Builder.Default
    private boolean verified = false;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_crawl_status")
    private CrawlStatus lastCrawlStatus;

    @Column(name = "last_crawled_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime lastCrawledAt;

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    private String lastErrorMessage;

    @Column(name = "consecutive_errors", nullable = false)
    @Builder.Default
    private int consecutiveErrors = 0;

    @Column(name = "crawl_frequency_hours")
    @Builder.Default
    private int crawlFrequencyHours = 4;

    private String source;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
