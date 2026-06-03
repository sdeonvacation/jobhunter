package dev.jobhub.model;

import dev.jobhub.model.enums.Confidence;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "resolution_result")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResolutionResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Company company;

    @Column(nullable = false)
    private String strategy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "candidate_urls", columnDefinition = "jsonb")
    private List<String> candidateUrls;

    @Column(name = "selected_url")
    private String selectedUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Confidence confidence;

    @Column(name = "ambiguity_reason")
    private String ambiguityReason;

    @Column(name = "resolved_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime resolvedAt;

    @Column(name = "needs_manual_review", nullable = false)
    @Builder.Default
    private boolean needsManualReview = false;
}
