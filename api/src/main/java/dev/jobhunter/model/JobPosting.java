package dev.jobhunter.model;

import dev.jobhunter.model.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "job_posting")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobSource source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "endpoint_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private CareerEndpoint endpoint;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(nullable = false)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Company company;

    private String location;

    @Column(name = "location_city")
    private String locationCity;

    @Column(name = "location_country")
    private String locationCountry;

    @Enumerated(EnumType.STRING)
    @Column(name = "is_remote")
    private RemoteType isRemote;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "apply_url")
    private String applyUrl;

    @Column(name = "posted_date")
    private LocalDate postedDate;

    @Column(name = "discovered_date")
    private LocalDate discoveredDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type")
    private EmploymentType employmentType;

    @Column(name = "salary_min", precision = 12, scale = 2)
    private BigDecimal salaryMin;

    @Column(name = "salary_max", precision = 12, scale = 2)
    private BigDecimal salaryMax;

    @Column(name = "salary_currency")
    private String salaryCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "salary_period")
    private SalaryPeriod salaryPeriod;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_content", columnDefinition = "jsonb")
    private Map<String, Object> rawContent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "external_links", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, String> externalLinks = new HashMap<>();

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "deactivated_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime deactivatedAt;

    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "language_filter")
    @Builder.Default
    private FilterDecision languageFilter = FilterDecision.KEEP;

    @Enumerated(EnumType.STRING)
    @Column(name = "visa_sponsorship")
    private VisaSponsorship visaSponsorship;

    @Column(name = "filter_reason")
    private String filterReason;

    @Column(name = "recruiter_name")
    private String recruiterName;

    @Column(name = "recruiter_email")
    private String recruiterEmail;

    @Column(name = "recruiter_data_expires_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime recruiterDataExpiresAt;

    @Column(name = "poster_name")
    private String posterName;

    @Column(name = "poster_title")
    private String posterTitle;

    @Column(name = "poster_linkedin_url")
    private String posterLinkedinUrl;

    @Column(name = "poster_avatar_url")
    private String posterAvatarUrl;

    @Column(name = "poster_contact_id")
    private UUID posterContactId;

    @Column(name = "last_crawled_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime lastCrawledAt;

    @Column(name = "applied", nullable = false)
    @Builder.Default
    private boolean applied = false;

    @Column(name = "applied_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime appliedAt;

    @Column(name = "hidden", nullable = false)
    @Builder.Default
    private boolean hidden = false;

    @Column(name = "required_yoe")
    private Integer requiredYoe;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "TIMESTAMP")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<JobSkill> skills = new ArrayList<>();

    @OneToOne(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private MatchScore matchScore;

    @OneToOne(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private OpportunityScore opportunityScore;

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
