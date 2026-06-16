package dev.jobhunter.people.service;

import dev.jobhunter.model.Company;
import dev.jobhunter.people.model.enums.VisaFriendliness;
import dev.jobhunter.repository.CompanyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
public class VisaSignalService {

    private final CompanyRepository companyRepository;

    public VisaSignalService(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    public VisaSignals getSignals(UUID companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + companyId));

        VisaFriendliness derived = deriveVisaFriendliness(
                company.getHasSponsoredBefore(),
                company.getEnglishSpeaking(),
                company.getInternationalWorkforce()
        );

        return new VisaSignals(
                companyId,
                company.getHasSponsoredBefore(),
                company.getEnglishSpeaking(),
                company.getInternationalWorkforce(),
                derived
        );
    }

    public void updateSignal(UUID companyId, String signal, boolean value) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new IllegalArgumentException("Company not found: " + companyId));

        switch (signal) {
            case "hasSponsoredBefore" -> company.setHasSponsoredBefore(value);
            case "englishSpeaking" -> company.setEnglishSpeaking(value);
            case "internationalWorkforce" -> company.setInternationalWorkforce(value);
            default -> throw new IllegalArgumentException("Unknown visa signal: " + signal);
        }

        VisaFriendliness derived = deriveVisaFriendliness(
                company.getHasSponsoredBefore(),
                company.getEnglishSpeaking(),
                company.getInternationalWorkforce()
        );
        company.setVisaFriendliness(derived.name());

        companyRepository.save(company);
        log.info("Updated visa signal '{}' = {} for company {}, derived: {}",
                signal, value, companyId, derived);
    }

    static VisaFriendliness deriveVisaFriendliness(Boolean hasSponsoredBefore,
                                                    Boolean englishSpeaking,
                                                    Boolean internationalWorkforce) {
        if (hasSponsoredBefore == null || englishSpeaking == null || internationalWorkforce == null) {
            return VisaFriendliness.UNKNOWN;
        }

        int trueCount = 0;
        if (hasSponsoredBefore) trueCount++;
        if (englishSpeaking) trueCount++;
        if (internationalWorkforce) trueCount++;

        return switch (trueCount) {
            case 3 -> VisaFriendliness.HIGH;
            case 2, 1 -> VisaFriendliness.MEDIUM;
            default -> VisaFriendliness.LOW;
        };
    }

    public record VisaSignals(
            UUID companyId,
            Boolean hasSponsoredBefore,
            Boolean englishSpeaking,
            Boolean internationalWorkforce,
            VisaFriendliness derived
    ) {}
}
