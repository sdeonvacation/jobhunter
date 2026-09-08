package dev.jobhunter.linkedin;

import java.util.List;
import java.util.Locale;

/**
 * Classifies a post author's role from their LinkedIn headline/title.
 * Pure logic, no Spring dependencies.
 */
public class AuthorRoleClassifier {

    private static final List<String> RECRUITER_KEYWORDS = List.of(
            "recruiter", "talent acquisition", "talent partner", "talent sourcer",
            "talent", "people", "hr", "human resources", "hiring"
    );
    private static final List<String> HIRING_MANAGER_KEYWORDS = List.of(
            "hiring manager", "head of", "director of", "engineering manager",
            "team lead", "hiring lead"
    );
    private static final List<String> FOUNDER_KEYWORDS = List.of(
            "founder", "co-founder", "ceo", "cto", "chief"
    );
    private static final List<String> ENG_LEAD_KEYWORDS = List.of(
            "engineering lead", "tech lead", "staff engineer", "principal engineer",
            "architect", "lead engineer", "lead developer"
    );

    /**
     * Classify a post author's role from their LinkedIn headline/title.
     * UNKNOWN when no keyword matches.
     */
    public AuthorRole classify(String authorTitle) {
        if (authorTitle == null || authorTitle.isBlank()) {
            return AuthorRole.UNKNOWN;
        }
        String title = authorTitle.toLowerCase(Locale.ROOT);
        // Specific hiring-manager phrases first: "hiring manager"/"hiring lead" must not be
        // swallowed by the generic "hiring" recruiter keyword.
        if (containsAny(title, HIRING_MANAGER_KEYWORDS)) {
            return AuthorRole.HIRING_MANAGER;
        }
        if (containsAny(title, RECRUITER_KEYWORDS)) {
            return AuthorRole.RECRUITER;
        }
        if (containsAny(title, FOUNDER_KEYWORDS)) {
            return AuthorRole.FOUNDER;
        }
        if (containsAny(title, ENG_LEAD_KEYWORDS)) {
            return AuthorRole.ENG_LEAD;
        }
        return AuthorRole.UNKNOWN;
    }

    private boolean containsAny(String title, List<String> keywords) {
        for (String keyword : keywords) {
            if (title.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
