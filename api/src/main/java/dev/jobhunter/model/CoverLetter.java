package dev.jobhunter.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "cover_letter")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CoverLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private JobPosting job;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    @Builder.Default
    private String tone = "professional";

    private String focus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> angles;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "keywords_mirrored", columnDefinition = "jsonb")
    private List<String> keywordsMirrored;

    @Column(nullable = false)
    @Builder.Default
    private int version = 1;

    @Column(name = "generated_at", columnDefinition = "TIMESTAMP", nullable = false)
    private LocalDateTime generatedAt;

    @Column(name = "edited_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime editedAt;
}
