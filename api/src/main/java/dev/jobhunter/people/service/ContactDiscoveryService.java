package dev.jobhunter.people.service;

import dev.jobhunter.linkedin.LinkedInNetworkingService;
import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.model.Company;
import dev.jobhunter.people.dto.ContactScore;
import dev.jobhunter.people.model.enums.ContactDiscoverySource;
import dev.jobhunter.people.model.ContactDiscoveryRun;
import dev.jobhunter.people.repository.ContactDiscoveryRunRepository;
import dev.jobhunter.repository.CompanyRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Discovers contacts at companies via LinkedIn search and scores them for outreach priority.
 */
@Slf4j
@Service
public class ContactDiscoveryService {

    private final LinkedInNetworkingService networkingService;
    private final OutreachContactRepository contactRepository;
    private final ContactDiscoveryRunRepository discoveryRunRepository;
    private final CompanyRepository companyRepository;
    private final ContactPriorityScorer scorer;

    public ContactDiscoveryService(LinkedInNetworkingService networkingService,
                                   OutreachContactRepository contactRepository,
                                   ContactDiscoveryRunRepository discoveryRunRepository,
                                   CompanyRepository companyRepository,
                                   ContactPriorityScorer scorer) {
        this.networkingService = networkingService;
        this.contactRepository = contactRepository;
        this.discoveryRunRepository = discoveryRunRepository;
        this.companyRepository = companyRepository;
        this.scorer = scorer;
    }

    /**
     * Discovers contacts at a company using LinkedIn search.
     * Scores new contacts and records the discovery run.
     */
    @Transactional
    public List<OutreachContact> discoverForCompany(UUID companyId, List<String> titleKeywords) {
        ContactDiscoveryRun run = ContactDiscoveryRun.builder()
                .company(companyRepository.getReferenceById(companyId))
                .source(ContactDiscoverySource.LINKEDIN_SEARCH)
                .build();

        try {
            List<OutreachContact> contacts = networkingService.findContacts(companyId, titleKeywords);

            // Count genuinely new contacts (not previously existing)
            List<OutreachContact> existingContacts = contactRepository.findByCompanyId(companyId);
            int previousCount = existingContacts.size();

            run.setContactsFound(contacts.size());
            run.setContactsNew(Math.max(0, contacts.size() - previousCount));

            // Score newly discovered contacts
            if (!contacts.isEmpty()) {
                List<ContactScore> scores = scorer.scoreBatch(contacts);
                log.info("Discovered {} contacts at company {}, {} new. Top score: {}",
                        contacts.size(), companyId, run.getContactsNew(),
                        scores.stream().mapToInt(ContactScore::contactPriorityScore).max().orElse(0));
            }

            discoveryRunRepository.save(run);
            return contacts;

        } catch (Exception e) {
            run.setContactsFound(0);
            run.setContactsNew(0);
            discoveryRunRepository.save(run);
            log.warn("Discovery failed for company {}: {}", companyId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Discovers contacts at top-priority companies that have no poster contacts.
     * Queries active companies by priorityScore DESC.
     */
    @Transactional
    public int discoverTopPriority(int maxCompanies) {
        var pageable = PageRequest.of(0, maxCompanies, Sort.by(Sort.Direction.DESC, "priorityScore"));
        var companies = companyRepository.findByIsActiveTrue(pageable);

        int discovered = 0;
        for (Company company : companies) {
            // Skip companies that already have contacts
            List<OutreachContact> existingContacts = contactRepository.findByCompanyId(company.getId());
            if (!existingContacts.isEmpty()) {
                continue;
            }

            List<String> defaultKeywords = List.of("recruiter", "hiring manager", "talent acquisition");
            List<OutreachContact> contacts = discoverForCompany(company.getId(), defaultKeywords);
            discovered += contacts.size();
        }

        log.info("Top priority discovery: {} companies checked, {} contacts discovered",
                companies.getNumberOfElements(), discovered);
        return discovered;
    }

    public List<ContactDiscoveryRun> getDiscoveryRuns(UUID companyId) {
        return discoveryRunRepository.findByCompanyIdOrderByRunAtDesc(companyId);
    }
}
