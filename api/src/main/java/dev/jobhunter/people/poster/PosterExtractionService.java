package dev.jobhunter.people.poster;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.people.crawl.PostCrawlHook;
import dev.jobhunter.people.model.enums.ContactDiscoverySource;
import dev.jobhunter.people.model.enums.Seniority;
import dev.jobhunter.repository.OutreachContactRepository;
import dev.jobhunter.repository.JobPostingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Post-crawl hook that extracts poster/recruiter info from job pages
 * and links them as OutreachContact entities to the job posting.
 */
@Slf4j
@Component
public class PosterExtractionService implements PostCrawlHook {

    private static final Pattern RECRUITER_PATTERN =
            Pattern.compile("\\b(?:recruit|talent\\s+acqui|sourcer|ta\\b)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MANAGER_PATTERN =
            Pattern.compile("\\b(?:manager|head\\s+of|lead)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECTOR_PATTERN =
            Pattern.compile("\\b(?:director|vp|vice\\s+president)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STAFF_PATTERN =
            Pattern.compile("\\b(?:staff|principal|distinguished)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENIOR_PATTERN =
            Pattern.compile("\\b(?:senior|sr\\.?)\\b", Pattern.CASE_INSENSITIVE);

    private final PosterExtractorRegistry registry;
    private final OutreachContactRepository contactRepository;
    private final JobPostingRepository jobPostingRepository;

    public PosterExtractionService(PosterExtractorRegistry registry,
                                   OutreachContactRepository contactRepository,
                                   JobPostingRepository jobPostingRepository) {
        this.registry = registry;
        this.contactRepository = contactRepository;
        this.jobPostingRepository = jobPostingRepository;
    }

    @Override
    public void afterJobPersisted(JobPosting job, String rawHtml, Map<String, Object> rawJson) {
        extractAndLink(job, rawHtml, rawJson);
    }

    @Override
    public int order() {
        return 10;
    }

    public Optional<OutreachContact> extractAndLink(JobPosting job, String rawHtml, Map<String, Object> rawJson) {
        try {
            AtsType atsType = resolveAtsType(job);
            if (atsType == null) {
                return Optional.empty();
            }

            Optional<PosterExtractor> extractor = registry.getExtractor(atsType);
            if (extractor.isEmpty()) {
                return Optional.empty();
            }

            Optional<PosterInfo> posterInfo = extractor.get().extract(rawHtml, rawJson);
            if (posterInfo.isEmpty()) {
                return Optional.empty();
            }

            PosterInfo info = posterInfo.get();

            // Link poster metadata to the job regardless of contact creation.
            job.setPosterName(info.name());
            job.setPosterTitle(info.title());
            job.setPosterLinkedinUrl(info.linkedinUrl());
            job.setPosterAvatarUrl(info.avatarUrl());

            // OutreachContact.linkedinUrl is NOT NULL + UNIQUE; without it there is no stable
            // identity/dedup key, so skip persisting a contact (name/title still linked above).
            Optional<OutreachContact> contact = upsertContact(info, job);
            contact.ifPresent(c -> job.setPosterContactId(c.getId()));
            jobPostingRepository.save(job);

            log.info("Extracted poster '{}' for job {} ({})", info.name(), job.getId(), atsType);
            return contact;

        } catch (Exception e) {
            log.warn("Poster extraction failed for job {}: {}", job.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private AtsType resolveAtsType(JobPosting job) {
        if (job.getEndpoint() != null) {
            return job.getEndpoint().getAtsType();
        }
        return null;
    }

    private Optional<OutreachContact> upsertContact(PosterInfo info, JobPosting job) {
        String linkedinUrl = info.linkedinUrl();
        if (linkedinUrl == null || linkedinUrl.isBlank()) {
            log.debug("No LinkedIn URL for poster '{}' — skipping OutreachContact creation for job {}",
                    info.name(), job.getId());
            return Optional.empty();
        }

        // Dedup by LinkedIn URL
        Optional<OutreachContact> existing = contactRepository.findByLinkedinUrl(linkedinUrl);
        if (existing.isPresent()) {
            OutreachContact contact = existing.get();
            // Update fields if new data available
            if (info.title() != null && contact.getTitle() == null) {
                contact.setTitle(info.title());
            }
            return Optional.of(contactRepository.save(contact));
        }

        // Create new contact
        OutreachContact contact = OutreachContact.builder()
                .personName(info.name())
                .title(info.title())
                .linkedinUrl(linkedinUrl)
                .company(job.getCompany())
                .discoveredVia(ContactDiscoverySource.JOB_POSTER)
                .seniority(inferSeniority(info.title()))
                .build();

        return Optional.of(contactRepository.save(contact));
    }

    /**
     * Infers seniority from a job title using regex patterns.
     */
    public Seniority inferSeniority(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        if (RECRUITER_PATTERN.matcher(title).find()) return Seniority.RECRUITER;
        if (DIRECTOR_PATTERN.matcher(title).find()) return Seniority.DIRECTOR;
        if (MANAGER_PATTERN.matcher(title).find()) return Seniority.MANAGER;
        if (STAFF_PATTERN.matcher(title).find()) return Seniority.STAFF;
        if (SENIOR_PATTERN.matcher(title).find()) return Seniority.SENIOR;
        return Seniority.IC;
    }
}
