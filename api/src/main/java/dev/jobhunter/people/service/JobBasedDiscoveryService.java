package dev.jobhunter.people.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.linkedin.ConnectionStatus;
import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.people.model.ContactDiscoveryRun;
import dev.jobhunter.people.model.enums.ContactDiscoverySource;
import dev.jobhunter.people.model.enums.Seniority;
import dev.jobhunter.people.repository.ContactDiscoveryRunRepository;
import dev.jobhunter.repository.CompanyRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Discovers real outreach contacts by extracting person names from job descriptions using AI.
 * Only creates contacts when actual human names are found in JD text.
 */
@Slf4j
@Service
public class JobBasedDiscoveryService {

    private static final String EXTRACTION_PROMPT = """
            Extract any real person names mentioned in this job posting that could be contacted about the role.
            Look for:
            - Recruiters (e.g. "Your recruiter: Jane Smith", "Contact: John Doe")
            - Hiring managers (e.g. "Hiring Manager: Alex Chen", "Report to: Maria Garcia")
            - Team leads mentioned by name
            - Contact persons (e.g. "Ansprechpartner: Thomas Mueller", "Questions? Ask Sarah")
            - People who posted the job (e.g. "Posted by: David Lee")
            
            Return a JSON array of objects. Each object must have:
            - "name": full name (first and last name required, skip if only first name)
            - "title": their role/title if mentioned, otherwise "Recruiter"
            - "seniority": one of RECRUITER, MANAGER, DIRECTOR, SENIOR, IC
            
            If NO real person names are found, return an empty array [].
            Do NOT invent names. Do NOT return generic titles without names.
            Only return people whose full name (first + last) is explicitly stated in the text.
            """;

    private final AiProvider aiProvider;
    private final JobPostingRepository jobPostingRepository;
    private final OutreachContactRepository contactRepository;
    private final CompanyRepository companyRepository;
    private final ContactDiscoveryRunRepository discoveryRunRepository;
    private final EmailInferenceService emailInferenceService;

    public JobBasedDiscoveryService(AiProvider aiProvider,
                                    JobPostingRepository jobPostingRepository,
                                    OutreachContactRepository contactRepository,
                                    CompanyRepository companyRepository,
                                    ContactDiscoveryRunRepository discoveryRunRepository,
                                    EmailInferenceService emailInferenceService) {
        this.aiProvider = aiProvider;
        this.jobPostingRepository = jobPostingRepository;
        this.contactRepository = contactRepository;
        this.companyRepository = companyRepository;
        this.discoveryRunRepository = discoveryRunRepository;
        this.emailInferenceService = emailInferenceService;
    }

    /**
     * Discover contacts for a company by scanning its job descriptions for real person names.
     */
    @Transactional
    public List<OutreachContact> discoverForCompany(UUID companyId) {
        Company company = companyRepository.findById(companyId).orElse(null);
        if (company == null) {
            log.warn("Company {} not found for JD-based discovery", companyId);
            return List.of();
        }

        if (!aiProvider.isAvailable()) {
            log.debug("AI provider not available for contact extraction");
            return contactRepository.findByCompanyId(companyId);
        }

        // Get recent job descriptions for this company
        var pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<JobPosting> jobs = jobPostingRepository.findByCompanyIdAndIsActiveTrue(companyId, pageable).getContent();

        if (jobs.isEmpty()) {
            log.debug("No active jobs for company {}", company.getName());
            return contactRepository.findByCompanyId(companyId);
        }

        // Concatenate JD snippets — focus on areas where contact info lives
        String jdText = jobs.stream()
                .filter(j -> j.getDescription() != null && !j.getDescription().isBlank())
                .map(j -> {
                    String desc = j.getDescription();
                    // Take last 2000 chars (contact info is almost always in final third)
                    String tail = desc.length() > 2000 ? desc.substring(desc.length() - 2000) : desc;
                    return "--- Job: " + j.getTitle() + " ---\n" + tail;
                })
                .limit(5) // Max 5 JDs to keep token cost low
                .collect(Collectors.joining("\n\n"));

        if (jdText.isBlank()) {
            return contactRepository.findByCompanyId(companyId);
        }

        // Extract names via AI
        List<ExtractedContact> extracted = extractContactsFromText(jdText);
        if (extracted.isEmpty()) {
            log.debug("No real contacts found in JDs for company {}", company.getName());
            return contactRepository.findByCompanyId(companyId);
        }

        String domain = extractCleanDomain(company);
        List<OutreachContact> created = new ArrayList<>();

        for (ExtractedContact ec : extracted) {
            // Skip if contact already exists (by name at this company)
            String normalizedName = ec.name.trim().toLowerCase();
            boolean exists = contactRepository.findByCompanyId(companyId).stream()
                    .anyMatch(c -> c.getPersonName() != null &&
                            c.getPersonName().trim().toLowerCase().equals(normalizedName));
            if (exists) continue;

            // Need a unique URL - use name-based slug
            String slug = normalizedName.replace(" ", "-").replaceAll("[^a-z0-9-]", "");
            String placeholderUrl = "https://linkedin.com/in/" + slug + "-" + companyId.toString().substring(0, 8);

            if (contactRepository.findByLinkedinUrl(placeholderUrl).isPresent()) continue;

            Seniority seniority = parseSeniority(ec.seniority);
            OutreachContact contact = OutreachContact.builder()
                    .company(company)
                    .linkedinUrl(placeholderUrl)
                    .personName(ec.name.trim())
                    .title(ec.title != null ? ec.title.trim() : "Recruiter")
                    .seniority(seniority)
                    .connectionStatus(ConnectionStatus.NONE)
                    .discoveredVia(ContactDiscoverySource.JOB_POSTER)
                    .interviewGenerationWeight(seniorityWeight(seniority))
                    .warmthScore(0)
                    .contactPriorityScore(seniorityWeight(seniority))
                    .build();

            contactRepository.save(contact);

            // Infer personal email if domain available
            if (domain != null) {
                emailInferenceService.inferAndSave(contact, domain);
            }

            created.add(contact);
        }

        // Record discovery run
        if (!created.isEmpty()) {
            ContactDiscoveryRun run = ContactDiscoveryRun.builder()
                    .company(company)
                    .source(ContactDiscoverySource.JOB_POSTER)
                    .contactsFound(created.size())
                    .contactsNew(created.size())
                    .build();
            discoveryRunRepository.save(run);

            log.info("JD extraction for {}: found {} real contacts (domain: {})",
                    company.getName(), created.size(), domain != null ? domain : "none");
        }

        return contactRepository.findByCompanyId(companyId);
    }

    /**
     * Discover contacts from a single job's description and link them to that job.
     */
    @Transactional
    public List<OutreachContact> discoverForJob(UUID jobId) {
        JobPosting job = jobPostingRepository.findById(jobId).orElse(null);
        if (job == null || job.getDescription() == null || job.getDescription().isBlank()) {
            return List.of();
        }

        if (!aiProvider.isAvailable()) {
            // Return any already-linked contacts for this job
            List<UUID> contactIds = contactRepository.findContactIdsByJobId(jobId);
            if (contactIds.isEmpty()) return List.of();
            return contactIds.stream()
                    .map(contactRepository::findById)
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::get)
                    .toList();
        }

        Company company = job.getCompany();
        if (company == null) return List.of();

        // Check if already discovered for this job
        List<UUID> existingIds = contactRepository.findContactIdsByJobId(jobId);
        if (!existingIds.isEmpty()) {
            return existingIds.stream()
                    .map(contactRepository::findById)
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::get)
                    .toList();
        }

        String desc = job.getDescription();
        String tail = desc.length() > 2000 ? desc.substring(desc.length() - 2000) : desc;
        String jdText = "--- Job: " + job.getTitle() + " at " + company.getName() + " ---\n" + tail;

        List<ExtractedContact> extracted = extractContactsFromText(jdText);
        if (extracted.isEmpty()) return List.of();

        String domain = extractCleanDomain(company);
        List<OutreachContact> result = new ArrayList<>();

        for (ExtractedContact ec : extracted) {
            String normalizedName = ec.name.trim().toLowerCase();

            // Check if contact already exists for this company
            OutreachContact existing = contactRepository.findByCompanyId(company.getId()).stream()
                    .filter(c -> c.getPersonName() != null && c.getPersonName().trim().toLowerCase().equals(normalizedName))
                    .findFirst().orElse(null);

            if (existing != null) {
                contactRepository.linkContactToJob(jobId, existing.getId());
                result.add(existing);
                continue;
            }

            String slug = normalizedName.replace(" ", "-").replaceAll("[^a-z0-9-]", "");
            String placeholderUrl = "https://linkedin.com/in/" + slug + "-" + company.getId().toString().substring(0, 8);

            if (contactRepository.findByLinkedinUrl(placeholderUrl).isPresent()) continue;

            Seniority seniority = parseSeniority(ec.seniority);
            OutreachContact contact = OutreachContact.builder()
                    .company(company)
                    .linkedinUrl(placeholderUrl)
                    .personName(ec.name.trim())
                    .title(ec.title != null ? ec.title.trim() : "Recruiter")
                    .seniority(seniority)
                    .connectionStatus(ConnectionStatus.NONE)
                    .discoveredVia(ContactDiscoverySource.JOB_POSTER)
                    .interviewGenerationWeight(seniorityWeight(seniority))
                    .warmthScore(0)
                    .contactPriorityScore(seniorityWeight(seniority))
                    .build();

            contactRepository.save(contact);
            if (domain != null) {
                emailInferenceService.inferAndSave(contact, domain);
            }

            contactRepository.linkContactToJob(jobId, contact.getId());
            result.add(contact);
        }

        if (!result.isEmpty()) {
            log.info("JD extraction for job {} at {}: found {} contacts",
                    job.getTitle(), company.getName(), result.size());
        }

        return result;
    }

    /**
     * Bulk discover contacts from top companies by match score.
     */
    @Transactional
    public int discoverFromTopCompanies(int maxCompanies) {
        if (!aiProvider.isAvailable()) {
            log.warn("AI provider not available for bulk JD discovery");
            return 0;
        }

        var pageable = PageRequest.of(0, maxCompanies, Sort.by(Sort.Direction.DESC, "avgMatchScore"));
        var companies = companyRepository.findByIsActiveTrue(pageable);

        int totalCreated = 0;
        for (Company company : companies) {
            // Skip if already has contacts
            if (!contactRepository.findByCompanyId(company.getId()).isEmpty()) continue;

            try {
                List<OutreachContact> contacts = discoverForCompany(company.getId());
                totalCreated += contacts.stream()
                        .filter(c -> c.getCreatedAt() != null)
                        .count();
            } catch (Exception e) {
                log.debug("Skipping company {} due to error: {}", company.getName(), e.getMessage());
            }
        }

        log.info("Bulk JD discovery: checked {} companies, created {} contacts",
                companies.getNumberOfElements(), totalCreated);
        return totalCreated;
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    private List<ExtractedContact> extractContactsFromText(String text) {
        try {
            // Strip HTML tags for cleaner AI input
            String cleaned = text.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ");
            if (cleaned.length() > 4000) {
                cleaned = cleaned.substring(0, 4000);
            }

            // Use generate() directly — extract() with List.class sends a wrong JSON schema
            // that makes Gemini return {} instead of [], causing parse failures
            String response = aiProvider.generate(EXTRACTION_PROMPT, cleaned);
            if (response == null || response.isBlank()) return List.of();

            // Extract JSON array from response (model might wrap in markdown)
            String json = response.trim();
            if (json.startsWith("```")) {
                int first = json.indexOf('\n');
                if (first > 0) json = json.substring(first + 1);
                if (json.endsWith("```")) json = json.substring(0, json.length() - 3).trim();
            }
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start == -1 || end == -1 || end <= start) return List.of();
            json = json.substring(start, end + 1);

            List<Map<String, String>> result = objectMapper.readValue(json, new TypeReference<>() {});
            if (result == null || result.isEmpty()) return List.of();

            return result.stream()
                    .filter(m -> m.get("name") != null && !m.get("name").isBlank())
                    .filter(m -> m.get("name").split("\\s+").length >= 2) // Must have first+last name
                    .map(m -> new ExtractedContact(
                            m.get("name"),
                            m.getOrDefault("title", "Recruiter"),
                            m.getOrDefault("seniority", "RECRUITER")
                    ))
                    .toList();
        } catch (Exception e) {
            log.debug("AI contact extraction failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String extractCleanDomain(Company company) {
        String domain = company.getDomain();
        if (domain == null || domain.isBlank()) return null;

        domain = domain.trim().toLowerCase();

        // Filter out ATS platform domains
        if (domain.contains("myworkdayjobs.com") || domain.contains("smartrecruiters.com") ||
                domain.contains("greenhouse.io") || domain.contains("lever.co") ||
                domain.contains("ashbyhq.com") || domain.contains("breezy.hr") ||
                domain.contains("recruitee.com") || domain.contains("personio.de") ||
                domain.contains("teamtailor.com") || domain.contains("jobs.eu")) {
            String[] parts = domain.split("\\.");
            if (parts.length > 2 && !parts[0].equals("www") && !parts[0].equals("jobs")) {
                return parts[0] + ".com";
            }
            return null;
        }

        if (domain.startsWith("www.")) domain = domain.substring(4);
        return domain;
    }

    private Seniority parseSeniority(String s) {
        if (s == null) return Seniority.RECRUITER;
        try {
            return Seniority.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Seniority.RECRUITER;
        }
    }

    private int seniorityWeight(Seniority s) {
        return switch (s) {
            case DIRECTOR -> 90;
            case MANAGER -> 85;
            case SENIOR -> 75;
            case STAFF -> 70;
            case IC -> 60;
            case RECRUITER -> 55;
        };
    }

    private record ExtractedContact(String name, String title, String seniority) {}
}
