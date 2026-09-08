package dev.jobhunter.linkedin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorRoleClassifierTest {

    private final AuthorRoleClassifier classifier = new AuthorRoleClassifier();

    @Test
    @DisplayName("Recruiter keyword group classifies as RECRUITER")
    void recruiterKeywords() {
        assertThat(classifier.classify("Senior Recruiter")).isEqualTo(AuthorRole.RECRUITER);
        assertThat(classifier.classify("Talent Acquisition Specialist")).isEqualTo(AuthorRole.RECRUITER);
        assertThat(classifier.classify("Talent Partner")).isEqualTo(AuthorRole.RECRUITER);
        assertThat(classifier.classify("People Operations")).isEqualTo(AuthorRole.RECRUITER);
        assertThat(classifier.classify("HR Business Partner")).isEqualTo(AuthorRole.RECRUITER);
        assertThat(classifier.classify("Human Resources Lead")).isEqualTo(AuthorRole.RECRUITER);
    }

    @Test
    @DisplayName("Hiring manager keyword group classifies as HIRING_MANAGER")
    void hiringManagerKeywords() {
        assertThat(classifier.classify("Head of Engineering")).isEqualTo(AuthorRole.HIRING_MANAGER);
        assertThat(classifier.classify("Director of Product")).isEqualTo(AuthorRole.HIRING_MANAGER);
        assertThat(classifier.classify("Engineering Manager")).isEqualTo(AuthorRole.HIRING_MANAGER);
        assertThat(classifier.classify("Team Lead")).isEqualTo(AuthorRole.HIRING_MANAGER);
    }

    @Test
    @DisplayName("Founder keyword group classifies as FOUNDER")
    void founderKeywords() {
        assertThat(classifier.classify("Founder & CEO")).isEqualTo(AuthorRole.FOUNDER);
        assertThat(classifier.classify("Co-founder")).isEqualTo(AuthorRole.FOUNDER);
        assertThat(classifier.classify("CTO")).isEqualTo(AuthorRole.FOUNDER);
        assertThat(classifier.classify("Chief Technology Officer")).isEqualTo(AuthorRole.FOUNDER);
    }

    @Test
    @DisplayName("Engineering lead keyword group classifies as ENG_LEAD")
    void engLeadKeywords() {
        assertThat(classifier.classify("Tech Lead")).isEqualTo(AuthorRole.ENG_LEAD);
        assertThat(classifier.classify("Staff Engineer")).isEqualTo(AuthorRole.ENG_LEAD);
        assertThat(classifier.classify("Principal Engineer")).isEqualTo(AuthorRole.ENG_LEAD);
        assertThat(classifier.classify("Lead Engineer")).isEqualTo(AuthorRole.ENG_LEAD);
        assertThat(classifier.classify("Software Architect")).isEqualTo(AuthorRole.ENG_LEAD);
    }

    @Test
    @DisplayName("Null, blank and unmatched titles classify as UNKNOWN")
    void nullBlankAndUnknown() {
        assertThat(classifier.classify(null)).isEqualTo(AuthorRole.UNKNOWN);
        assertThat(classifier.classify("")).isEqualTo(AuthorRole.UNKNOWN);
        assertThat(classifier.classify("   ")).isEqualTo(AuthorRole.UNKNOWN);
        assertThat(classifier.classify("Software Engineer")).isEqualTo(AuthorRole.UNKNOWN);
        assertThat(classifier.classify("Product Manager")).isEqualTo(AuthorRole.UNKNOWN);
    }

    @Test
    @DisplayName("Classification is case-insensitive")
    void caseInsensitive() {
        assertThat(classifier.classify("RECRUITER")).isEqualTo(AuthorRole.RECRUITER);
        assertThat(classifier.classify("Head Of Engineering")).isEqualTo(AuthorRole.HIRING_MANAGER);
        assertThat(classifier.classify("FOUNDER")).isEqualTo(AuthorRole.FOUNDER);
        assertThat(classifier.classify("TECH LEAD")).isEqualTo(AuthorRole.ENG_LEAD);
    }

    @Test
    @DisplayName("'hiring manager' phrase wins over generic 'hiring' recruiter keyword")
    void hiringManagerPhrasePrecedence() {
        // HIRING_MANAGER phrases are checked before the generic "hiring" RECRUITER keyword
        assertThat(classifier.classify("Hiring Manager")).isEqualTo(AuthorRole.HIRING_MANAGER);
        // Generic "hiring" titles still classify as RECRUITER
        assertThat(classifier.classify("Hiring Specialist")).isEqualTo(AuthorRole.RECRUITER);
    }
}