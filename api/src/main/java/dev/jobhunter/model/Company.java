package dev.jobhunter.model;

import dev.jobhunter.model.enums.CompanyStatus;
import dev.jobhunter.model.enums.DiscoverySource;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "company")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "normalized_name", nullable = false, unique = true)
    private String normalizedName;

    private String domain;

    private String country;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CompanyStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "discovered_via")
    private DiscoverySource discoveredVia;

    @Column(name = "discovered_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime discoveredAt;

    @Column(name = "avg_match_score")
    private Integer avgMatchScore;

    @Column(name = "interview_rate")
    @Builder.Default
    private Double interviewRate = 0.0;

    @Column(name = "total_applications")
    @Builder.Default
    private int totalApplications = 0;

    @Column(name = "total_interviews")
    @Builder.Default
    private int totalInterviews = 0;

    @Column(name = "priority_score")
    @Builder.Default
    private Double priorityScore = 50.0;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime updatedAt;

    @Column(name = "linkedin_url")
    private String linkedinUrl;

    @Column(name = "industry")
    private String industry;

    @Column(name = "employee_count")
    private Integer employeeCount;

    @Column(name = "specialties", columnDefinition = "TEXT")
    private String specialties;

    @Column(name = "recent_posts_summary", columnDefinition = "TEXT")
    private String recentPostsSummary;

    @Column(name = "linkedin_enriched_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime linkedinEnrichedAt;

    @OneToMany(mappedBy = "company", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<CareerEndpoint> careerEndpoints = new ArrayList<>();

    @OneToMany(mappedBy = "company", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<JobPosting> jobPostings = new ArrayList<>();

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
