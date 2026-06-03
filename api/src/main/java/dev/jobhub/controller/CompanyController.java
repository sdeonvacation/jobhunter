package dev.jobhub.controller;

import dev.jobhub.dto.CompanyDetailDto;
import dev.jobhub.dto.CompanySummaryDto;
import dev.jobhub.dto.DtoMapper;
import dev.jobhub.model.Company;
import dev.jobhub.model.JobPosting;
import dev.jobhub.model.enums.CompanyStatus;
import dev.jobhub.repository.CompanyRepository;
import dev.jobhub.repository.JobPostingRepository;
import dev.jobhub.service.CompanyService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/companies")
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class CompanyController {

    private final CompanyRepository companyRepository;
    private final CompanyService companyService;
    private final JobPostingRepository jobPostingRepository;

    public CompanyController(CompanyRepository companyRepository,
                             CompanyService companyService,
                             JobPostingRepository jobPostingRepository) {
        this.companyRepository = companyRepository;
        this.companyService = companyService;
        this.jobPostingRepository = jobPostingRepository;
    }

    @GetMapping
    public Page<CompanySummaryDto> listCompanies(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "priorityScore") String sort) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, sort));

        List<Company> companies;
        if (status != null && !status.isBlank()) {
            CompanyStatus companyStatus = CompanyStatus.valueOf(status.toUpperCase());
            companies = companyRepository.findByStatus(companyStatus);
        } else {
            companies = companyRepository.findByIsActiveTrue();
        }

        List<CompanySummaryDto> dtos = companies.stream()
                .map(DtoMapper::toCompanySummary)
                .toList();

        // Manual pagination since repo returns List
        int start = Math.min((int) pageable.getOffset(), dtos.size());
        int end = Math.min(start + pageable.getPageSize(), dtos.size());
        List<CompanySummaryDto> pageContent = dtos.subList(start, end);

        return new PageImpl<>(pageContent, pageable, dtos.size());
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
    public ResponseEntity<CompanySummaryDto> addCompany(@RequestBody AddCompanyRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Company company = new Company();
        company.setId(UUID.randomUUID());
        company.setName(request.name());
        company.setDomain(request.domain());
        company.setCountry(request.country());
        company.setStatus(CompanyStatus.DISCOVERED);
        company.setActive(true);

        Company saved = companyService.save(company);
        return ResponseEntity.ok(DtoMapper.toCompanySummary(saved));
    }

    public record AddCompanyRequest(String name, String domain, String country, String careersUrl) {}
}
