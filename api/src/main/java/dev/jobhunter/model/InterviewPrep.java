package dev.jobhunter.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "interview_prep")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterviewPrep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false, unique = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private JobPosting job;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "talking_points", columnDefinition = "jsonb")
    private List<Map<String, Object>> talkingPoints;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mapped_story_ids", columnDefinition = "jsonb")
    private List<UUID> mappedStoryIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "company_research", columnDefinition = "jsonb")
    private Map<String, Object> companyResearch;

    @Column(name = "prepared_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime preparedAt;
}
