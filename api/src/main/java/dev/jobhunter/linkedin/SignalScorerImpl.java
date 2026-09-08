package dev.jobhunter.linkedin;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scores a candidate LinkedIn post against a job context using lightweight
 * textual signals. Pure logic, no Spring dependencies.
 */
public class SignalScorerImpl implements SignalScorer {

    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "of", "in", "at", "to", "a", "an", "is", "are",
            "we", "our", "you", "your", "new", "join", "team", "looking", "hiring",
            "role", "position", "job", "opening", "opportunity", "time", "part", "work",
            "company", "about", "this", "that", "have", "has", "will", "can", "must",
            "should", "would", "could", "be", "by", "from", "on", "as", "or",
            "level", "full", "stack", "remote", "based", "mid", "entry"
    );

    private static final Set<String> LEGAL_SUFFIXES = Set.of(
            "gmbh", "ag", "inc", "ltd", "llc", "co", "corp", "sarl", "bv", "se",
            "plc", "limited", "ug"
    );

    private static final Pattern RELATIVE_TIME_PATTERN =
            Pattern.compile("(\\d+)\\s+(day|week|month)s?\\s+ago");

    private static final int RECENCY_DAYS = 30;

    private final AuthorRoleClassifier roleClassifier = new AuthorRoleClassifier();

    @Override
    public SignalScores score(CandidatePost post, JobContextResolver.JobContext ctx) {
        boolean linkMatch = linkMatch(post, ctx);
        boolean companyNameMatch = companyNameMatch(post, ctx);
        boolean fuzzyTitleMatch = fuzzyTitleMatch(post, ctx);
        AuthorRole authorRole = roleClassifier.classify(post.authorTitle());
        boolean recencyMatch = recencyMatch(post.postedAt());
        return new SignalScores(linkMatch, companyNameMatch, fuzzyTitleMatch, authorRole, recencyMatch);
    }

    @Override
    public RecruiterPostVerdict verdict(SignalScores scores) {
        if (scores.linkMatch()) {
            return RecruiterPostVerdict.HIGH;
        }
        if (scores.companyNameMatch() && scores.fuzzyTitleMatch() && scores.authorRole() != AuthorRole.UNKNOWN) {
            return RecruiterPostVerdict.HIGH;
        }
        if (scores.companyNameMatch() && scores.fuzzyTitleMatch()) {
            return RecruiterPostVerdict.MEDIUM;
        }
        return RecruiterPostVerdict.UNCERTAIN;
    }

    private boolean linkMatch(CandidatePost post, JobContextResolver.JobContext ctx) {
        String snippet = normalize(post.snippet());
        String postUrl = normalize(post.postUrl());

        String applyUrl = ctx.applyUrl();
        if (applyUrl != null && !applyUrl.isBlank()) {
            String normalizedApply = normalize(applyUrl);
            if (containsIgnoreCase(snippet, normalizedApply) || containsIgnoreCase(postUrl, normalizedApply)) {
                return true;
            }
            // ATS URL: match host + path prefix
            String hostPath = atsHostPath(applyUrl);
            if (hostPath != null && (containsIgnoreCase(snippet, hostPath) || containsIgnoreCase(postUrl, hostPath))) {
                return true;
            }
        }

        String linkedinJobId = ctx.linkedinJobId();
        if (linkedinJobId != null && !linkedinJobId.isBlank()) {
            String linkedinView = "linkedin.com/jobs/view/" + linkedinJobId;
            if (containsIgnoreCase(snippet, linkedinView) || containsIgnoreCase(postUrl, linkedinView)) {
                return true;
            }
        }
        return false;
    }

    private boolean companyNameMatch(CandidatePost post, JobContextResolver.JobContext ctx) {
        String company = ctx.company();
        if (company == null || company.isBlank()) {
            return false;
        }
        String normalizedCompany = normalizeCompany(company);
        if (normalizedCompany.isBlank()) {
            return false;
        }
        String snippet = normalize(post.snippet());
        if (snippet.isBlank()) {
            return false;
        }
        if (normalizedCompany.length() < 4) {
            return snippet.contains(normalizedCompany);
        }
        if (snippet.contains(normalizedCompany)) {
            return true;
        }
        // Match on 2+ significant tokens of the company name
        List<String> tokens = significantTokens(normalizedCompany);
        if (tokens.size() < 2) {
            return false;
        }
        int matched = 0;
        for (String token : tokens) {
            if (snippet.contains(token)) {
                matched++;
            }
        }
        return matched >= 2;
    }

    private boolean fuzzyTitleMatch(CandidatePost post, JobContextResolver.JobContext ctx) {
        String title = ctx.title();
        if (title == null || title.isBlank()) {
            return false;
        }
        String normalizedTitle = normalize(title);
        if (normalizedTitle.isBlank()) {
            return false;
        }
        String snippet = normalize(post.snippet());
        if (snippet.isBlank()) {
            return false;
        }
        if (snippet.contains(normalizedTitle)) {
            return true;
        }
        List<String> tokens = significantTokens(normalizedTitle);
        int matched = 0;
        for (String token : tokens) {
            if (snippet.contains(token)) {
                matched++;
            }
        }
        return matched >= 2;
    }

    private boolean recencyMatch(String postedAt) {
        if (postedAt == null || postedAt.isBlank()) {
            return true;
        }
        LocalDate date = parseDate(postedAt);
        if (date == null) {
            return true; // don't penalize unknown dates
        }
        return !date.isBefore(LocalDate.now().minusDays(RECENCY_DAYS));
    }

    private LocalDate parseDate(String text) {
        String trimmed = text.trim();
        try {
            return LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDate();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        Matcher m = RELATIVE_TIME_PATTERN.matcher(trimmed.toLowerCase(Locale.ROOT));
        if (m.find()) {
            int amount = Integer.parseInt(m.group(1));
            String unit = m.group(2);
            return switch (unit) {
                case "day" -> LocalDate.now().minusDays(amount);
                case "week" -> LocalDate.now().minusWeeks(amount);
                case "month" -> LocalDate.now().minusMonths(amount);
                default -> null;
            };
        }
        return null;
    }

    private String atsHostPath(String applyUrl) {
        try {
            java.net.URI uri = java.net.URI.create(applyUrl);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host == null || host.isBlank()) {
                return null;
            }
            String hostPath = host + (path != null ? path : "");
            return hostPath.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> significantTokens(String normalized) {
        return java.util.Arrays.stream(normalized.split("\\s+"))
                .filter(t -> t.length() >= 3 && !STOPWORDS.contains(t))
                .toList();
    }

    private String normalizeCompany(String company) {
        String normalized = normalize(company);
        for (String suffix : LEGAL_SUFFIXES) {
            normalized = normalized.replaceAll("\\s+" + Pattern.quote(suffix) + "$", "");
        }
        return normalized.trim();
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean containsIgnoreCase(String haystack, String needle) {
        return !needle.isBlank() && haystack.contains(needle);
    }
}