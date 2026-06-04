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
                    "architect",
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
                    "data\\s+engineer",
                    "\\bml\\b",
                    "machine\\s+learning",
                    "\\bsde\\b",
                    "tech\\s+lead",
                    "technical",
                    "engineering",
                    "\\bcto\\b",
                    "vp\\s+engineering",
                    "\\bqa\\b",
                    "\\bsdet\\b",
                    "security",
                    "site\\s+reliability",
                    "solutions\\s+architect",
                    "system\\s+architect",
                    "\\bdevsecops\\b",
                    "\\bsys\\s*admin\\b",
                    "\\bkubernetes\\b",
                    "\\bk8s\\b"
            ),
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public FilterResult filter(String jobTitle) {
        if (jobTitle == null || jobTitle.isBlank()) {
            return FilterResult.keep();
        }

        if (ENGINEERING_PATTERN.matcher(jobTitle).find()) {
            return FilterResult.keep();
        }

        return FilterResult.skip("non-engineering role");
    }
}
