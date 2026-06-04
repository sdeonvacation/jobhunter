package dev.jobhub.filter;

import dev.jobhub.model.enums.FilterDecision;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RoleRelevanceFilterImplTest {

    private static RoleRelevanceFilterImpl filter;

    @BeforeAll
    static void setUp() {
        filter = new RoleRelevanceFilterImpl();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Senior Backend Engineer",
            "Staff Platform Engineer",
            "DevOps Engineer",
            "Data Engineer",
            "Software Developer",
            "SRE",
            "Frontend Developer",
            "Machine Learning Engineer",
            "Cloud Infrastructure Engineer",
            "Full-Stack Developer",
            "Full Stack Engineer",
            "Fullstack Engineer",
            "Back-End Developer",
            "Front-End Engineer",
            "Security Engineer",
            "QA Engineer",
            "SDET",
            "Site Reliability Engineer",
            "Tech Lead",
            "SDE II",
            "CTO",
            "Sales Engineer",
            "DevSecOps Engineer",
            "Platform Engineer",
            "Kubernetes Engineer",
            "K8s Platform Lead",
            "ML Engineer",
            "Senior Software Engineer - Backend",
            "Staff Engineer, Infrastructure"
    })
    void engineeringTitles_keep(String title) {
        FilterResult result = filter.filter(title);

        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.reason()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Accounts Payable Analyst",
            "HR Manager",
            "Marketing Director",
            "Recruiter",
            "Legal Counsel",
            "Graphic Designer",
            "Content Writer",
            "Office Manager",
            "Sales Representative",
            "Financial Controller",
            "Talent Acquisition Specialist",
            "Customer Success Manager",
            "Business Development Representative",
            "Executive Assistant",
            "Payroll Specialist",
            "Supply Chain Manager",
            "Compliance Officer",
            "Engineering Manager",
            "Solutions Architect",
            "Technical Program Manager",
            "System Architect",
            "Data Analyst"
    })
    void nonEngineeringTitles_skip(String title) {
        FilterResult result = filter.filter(title);

        assertThat(result.decision()).isEqualTo(FilterDecision.SKIP);
        assertThat(result.reason()).isEqualTo("non-engineering role");
    }

    @Test
    void nullTitle_keep() {
        FilterResult result = filter.filter(null);

        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.reason()).isNull();
    }

    @Test
    void emptyTitle_keep() {
        FilterResult result = filter.filter("");

        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.reason()).isNull();
    }

    @Test
    void blankTitle_keep() {
        FilterResult result = filter.filter("   ");

        assertThat(result.decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(result.reason()).isNull();
    }

    @Test
    void caseInsensitive_keep() {
        assertThat(filter.filter("SENIOR SOFTWARE ENGINEER").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("backend developer").decision()).isEqualTo(FilterDecision.KEEP);
        assertThat(filter.filter("DevOps ENGINEER").decision()).isEqualTo(FilterDecision.KEEP);
    }
}
