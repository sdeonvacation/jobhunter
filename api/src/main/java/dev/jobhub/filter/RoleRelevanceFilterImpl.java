package dev.jobhub.filter;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class RoleRelevanceFilterImpl implements RoleRelevanceFilter {

    private static final Pattern ENGINEERING_PATTERN = Pattern.compile(
            String.join("|",
                    "engineer",
                    "developer",
                    "programmer",
                    "\\bsre\\b",
                    "devops",
                    "dev\\s*ops",
                    "software",
                    "backend",
                    "back[\\s-]end",
                    "frontend",
                    "front[\\s-]end",
                    "fullstack",
                    "full[\\s-]stack",
                    "platform",
                    "infrastructure",
                    "\\bcloud\\b",
                    "\\bml\\b",
                    "machine\\s+learning",
                    "\\bsde\\b",
                    "tech\\s+lead",
                    "\\bcto\\b",
                    "\\bqa\\b",
                    "\\bsdet\\b",
                    "site\\s+reliability",
                    "\\bdevsecops\\b",
                    "\\bsys\\s*admin\\b",
                    "\\bkubernetes\\b",
                    "\\bk8s\\b"
            ),
            Pattern.CASE_INSENSITIVE
    );

    // Titles containing these words are excluded even if they match engineering pattern
    private static final Pattern EXCLUDED_ROLES_PATTERN = Pattern.compile(
            String.join("|",
                    "\\bmanager\\b",
                    "\\barchitect\\b",
                    "\\banalyst\\b"
            ),
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public FilterResult filter(String jobTitle) {
        if (jobTitle == null || jobTitle.isBlank()) {
            return FilterResult.keep();
        }

        // Exclusions take priority
        if (EXCLUDED_ROLES_PATTERN.matcher(jobTitle).find()) {
            return FilterResult.skip("non-engineering role");
        }

        if (ENGINEERING_PATTERN.matcher(jobTitle).find()) {
            return FilterResult.keep();
        }

        return FilterResult.skip("non-engineering role");
    }
}
