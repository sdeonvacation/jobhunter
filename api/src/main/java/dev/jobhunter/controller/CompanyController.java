package dev.jobhunter.controller;

import dev.jobhunter.dto.CompanyDetailDto;
import dev.jobhunter.dto.CompanySummaryDto;
import dev.jobhunter.dto.DtoMapper;
import dev.jobhunter.model.CareerEndpoint;
import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.CompanyStatus;
import dev.jobhunter.model.enums.Confidence;
import dev.jobhunter.model.enums.DiscoverySource;
import dev.jobhunter.repository.CareerEndpointRepository;
import dev.jobhunter.repository.CompanyRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.service.CompanyService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/companies")
@Transactional(readOnly = true)
public class CompanyController {

    private final CompanyRepository companyRepository;
    private final CompanyService companyService;
    private final JobPostingRepository jobPostingRepository;
    private final CareerEndpointRepository careerEndpointRepository;

    public CompanyController(CompanyRepository companyRepository,
                             CompanyService companyService,
                             JobPostingRepository jobPostingRepository,
                             CareerEndpointRepository careerEndpointRepository) {
        this.companyRepository = companyRepository;
        this.companyService = companyService;
        this.jobPostingRepository = jobPostingRepository;
        this.careerEndpointRepository = careerEndpointRepository;
    }

    @GetMapping
    public Page<CompanySummaryDto> listCompanies(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "priorityScore") String sort) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, sort));

        Page<Company> companies;
        boolean hasStatus = status != null && !status.isBlank();
        boolean hasSearch = search != null && !search.isBlank();

        if (hasStatus && hasSearch) {
            CompanyStatus companyStatus = CompanyStatus.valueOf(status.toUpperCase());
            companies = companyRepository.findByStatusAndNameContaining(companyStatus, search, pageable);
        } else if (hasStatus) {
            CompanyStatus companyStatus = CompanyStatus.valueOf(status.toUpperCase());
            companies = companyRepository.findByStatus(companyStatus, pageable);
        } else if (hasSearch) {
            companies = companyRepository.findByIsActiveTrueAndNameContaining(search, pageable);
        } else {
            companies = companyRepository.findByIsActiveTrue(pageable);
        }

        return companies.map(DtoMapper::toCompanySummary);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CompanyDetailDto> getCompany(@PathVariable UUID id) {
        Optional<Company> companyOpt = companyRepository.findById(id);
        if (companyOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Company company = companyOpt.get();
        Page<JobPosting> activeJobs = jobPostingRepository.findByCompanyIdAndIsActiveTrue(
                id, PageRequest.of(0, 1));
        int activeJobCount = (int) activeJobs.getTotalElements();

        return ResponseEntity.ok(DtoMapper.toCompanyDetail(company, activeJobCount));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<CompanySummaryDto> addCompany(@RequestBody AddCompanyRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        // Check if company already exists by normalized name
        Optional<Company> existing = companyService.findByNormalizedName(request.name());
        Company company;
        if (existing.isPresent()) {
            company = existing.get();
        } else {
            company = new Company();
            company.setName(request.name());
            company.setDomain(request.domain());
            company.setCountry(request.country());
            company.setStatus(CompanyStatus.DISCOVERED);
            company.setDiscoveredVia(DiscoverySource.MANUAL);
            company.setDiscoveredAt(LocalDateTime.now());
            company.setActive(true);
            company = companyService.save(company);
        }

        // Create career endpoint if URL provided and not already registered
        if (request.careersUrl() != null && !request.careersUrl().isBlank()) {
            boolean endpointExists = careerEndpointRepository.findByCompanyId(company.getId())
                    .stream().anyMatch(ep -> ep.getUrl().equalsIgnoreCase(request.careersUrl()));
            if (!endpointExists) {
                AtsType atsType = request.atsType() != null ? request.atsType() : AtsType.UNKNOWN;
                CareerEndpoint endpoint = CareerEndpoint.builder()
                        .company(company)
                        .url(request.careersUrl())
                        .atsType(atsType)
                        .confidence(Confidence.MEDIUM)
                        .verified(false)
                        .isActive(true)
                        .source("mcp")
                        .build();
                careerEndpointRepository.save(endpoint);
            }
        }

        return ResponseEntity.ok(DtoMapper.toCompanySummary(company));
    }

    @PatchMapping("/{id}/priority")
    @Transactional
    public ResponseEntity<Void> updatePriority(@PathVariable UUID id, @RequestBody PriorityRequest request) {
        if (request.priority() < 1 || request.priority() > 5) {
            return ResponseEntity.badRequest().build();
        }
        Optional<Company> opt = companyRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Company company = opt.get();
        company.setPriorityScore((double) request.priority());
        companyRepository.save(company);
        return ResponseEntity.ok().build();
    }

    public record AddCompanyRequest(String name, String domain, String country, String careersUrl, AtsType atsType) {}

    public record PriorityRequest(int priority) {}
}
