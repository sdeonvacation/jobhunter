package dev.jobhunter.linkedin;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "recruiter_post_check")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecruiterPostCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_url", nullable = false, unique = true)
    private String jobUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false)
    private RecruiterPostVerdict verdict;

    @Column(name = "confidence", nullable = false)
    private double confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_data", columnDefinition = "jsonb")
    private Map<String, Object> resultData;

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}