package dev.jobhunter.people.service;

import dev.jobhunter.linkedin.OutreachContact;
import dev.jobhunter.people.dto.EmailSuggestion;
import dev.jobhunter.people.model.enums.EmailConfidence;
import dev.jobhunter.repository.OutreachContactRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Infers email addresses from a contact's name and the company domain
 * using common corporate email patterns.
 */
@Slf4j
@Service
public class EmailInferenceService {

    private final OutreachContactRepository contactRepository;

    public EmailInferenceService(OutreachContactRepository contactRepository) {
        this.contactRepository = contactRepository;
    }

    /**
     * Generate email suggestions based on name patterns and company domain.
     * Returns ordered list from highest to lowest confidence.
     */
    public List<EmailSuggestion> inferEmails(OutreachContact contact, String companyDomain) {
        if (companyDomain == null || companyDomain.isBlank()) {
            return List.of();
        }
        if (contact.getPersonName() == null || contact.getPersonName().isBlank()) {
            return List.of();
        }

        String[] nameParts = parseNameParts(contact.getPersonName());
        String firstName = nameParts[0];
        String lastName = nameParts[1];

        if (firstName.isEmpty() || lastName.isEmpty()) {
            return List.of();
        }

        String domain = companyDomain.toLowerCase(Locale.ROOT).trim();
        List<EmailSuggestion> suggestions = new ArrayList<>();

        // firstname.lastname@domain — most common corporate pattern
        suggestions.add(new EmailSuggestion(
                firstName + "." + lastName + "@" + domain,
                "firstname.lastname",
                EmailConfidence.HIGH
        ));

        // firstname@domain
        suggestions.add(new EmailSuggestion(
                firstName + "@" + domain,
                "firstname",
                EmailConfidence.MEDIUM
        ));

        // f.lastname@domain
        suggestions.add(new EmailSuggestion(
                firstName.charAt(0) + "." + lastName + "@" + domain,
                "f.lastname",
                EmailConfidence.MEDIUM
        ));

        // flastname@domain
        suggestions.add(new EmailSuggestion(
                firstName.charAt(0) + lastName + "@" + domain,
                "flastname",
                EmailConfidence.LOW
        ));

        // firstname_lastname@domain
        suggestions.add(new EmailSuggestion(
                firstName + "_" + lastName + "@" + domain,
                "firstname_lastname",
                EmailConfidence.LOW
        ));

        return suggestions;
    }

    /**
     * Infers email using highest-confidence pattern and persists to entity.
     */
    public void inferAndSave(OutreachContact contact, String companyDomain) {
        List<EmailSuggestion> suggestions = inferEmails(contact, companyDomain);
        if (suggestions.isEmpty()) {
            log.debug("No email inference possible for contact: {}", contact.getPersonName());
            return;
        }

        // Already has email set — skip
        if (contact.getEmail() != null && !contact.getEmail().isBlank()) {
            return;
        }

        EmailSuggestion best = suggestions.get(0); // highest confidence first
        contact.setEmail(best.email());
        contact.setEmailConfidence(best.confidence());
        contactRepository.save(contact);

        log.debug("Inferred email for {}: {} ({})", contact.getPersonName(), best.email(), best.confidence());
    }

    /**
     * Parse "FirstName LastName" into [firstname, lastname], lowercased.
     * Handles multi-word last names by using first token as firstName, rest joined as lastName.
     */
    static String[] parseNameParts(String fullName) {
        String cleaned = fullName.trim()
                .replaceAll("[^a-zA-Z\\s-]", "") // remove non-alpha except spaces and hyphens
                .replaceAll("\\s+", " ");

        String[] tokens = cleaned.split(" ");
        if (tokens.length < 2) {
            return new String[]{ cleaned.toLowerCase(Locale.ROOT), "" };
        }

        String firstName = tokens[0].toLowerCase(Locale.ROOT);
        // Join remaining tokens for last name (handles "van der Berg" etc.)
        String lastName = String.join("", java.util.Arrays.copyOfRange(tokens, 1, tokens.length))
                .toLowerCase(Locale.ROOT)
                .replace("-", "");

        return new String[]{ firstName, lastName };
    }
}
