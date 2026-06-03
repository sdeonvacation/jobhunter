package dev.jobhub.service;

import dev.jobhub.model.Company;
import dev.jobhub.repository.CompanyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Transactional(readOnly = true)
public class CompanyService {

    private static final Pattern SUFFIX_PATTERN = Pattern.compile(
            "(?i)\\s*(gmbh|se|ag|inc\\.?|ltd\\.?|corp\\.?|llc|co\\.?|plc|sarl|srl|bv|nv)\\s*$"
    );

    private final CompanyRepository companyRepository;

    public CompanyService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    public Optional<Company> findById(UUID id) {
        return companyRepository.findById(id);
    }

    public Optional<Company> findByNormalizedName(String name) {
        return companyRepository.findByNormalizedName(normalizeCompanyName(name));
    }

    @Transactional
    public Company save(Company company) {
        if (company.getNormalizedName() == null) {
            company.setNormalizedName(normalizeCompanyName(company.getName()));
        }
        return companyRepository.save(company);
    }

    public String normalizeCompanyName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String normalized = SUFFIX_PATTERN.matcher(name).replaceAll("");
        return normalized.toLowerCase().trim();
    }
}
