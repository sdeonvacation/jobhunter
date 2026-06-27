package dev.jobhunter.model;

import dev.jobhunter.model.enums.EvaluationArchetype;
import dev.jobhunter.model.enums.LegitimacyTier;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "job_evaluation")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false, unique = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private JobPosting job;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "role_summary", columnDefinition = "jsonb")
    private Map<String, Object> roleSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cv_match", columnDefinition = "jsonb")
    private Map<String, Object> cvMatch;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "level_strategy", columnDefinition = "jsonb")
    private Map<String, Object> levelStrategy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "comp_research", columnDefinition = "jsonb")
    private Map<String, Object> compResearch;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "customization_plan", columnDefinition = "jsonb")
    private Map<String, Object> customizationPlan;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "interview_plan", columnDefinition = "jsonb")
    private Map<String, Object> interviewPlan;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "legitimacy", columnDefinition = "jsonb")
    private Map<String, Object> legitimacy;

    @Column(name = "overall_score", nullable = false)
    private int overallScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "archetype")
    private EvaluationArchetype archetype;

    @Enumerated(EnumType.STRING)
    @Column(name = "legitimacy_tier")
    private LegitimacyTier legitimacyTier;

    @Column(name = "description_fingerprint")
    private String descriptionFingerprint;

    @Column(name = "evaluated_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime evaluatedAt;
}
