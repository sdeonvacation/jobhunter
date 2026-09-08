package dev.jobhunter.linkedin;

import dev.jobhunter.model.enums.AtsType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignalScorerImplTest {

    private final SignalScorerImpl scorer = new SignalScorerImpl();

    private JobContextResolver.JobContext ctx(String title, String company, String applyUrl) {
        return new JobContextResolver.JobContext(title, company, "Berlin", null, applyUrl, AtsType.LEVER, null, null);
    }

    private SignalScorer.CandidatePost post(String snippet, String authorTitle) {
        return new SignalScorer.CandidatePost("https://www.linkedin.com/posts/1", "Jane", authorTitle,
                "https://linkedin.com/in/jane", snippet, "2 days ago");
    }

    @Test
    @DisplayName("Snippet containing the apply URL yields HIGH")
    void linkMatchYieldsHigh() {
        JobContextResolver.JobContext ctx = ctx("Java Engineer", "Acme", "https://jobs.lever.co/acme/123");
        SignalScorer.CandidatePost p = post("We are hiring! Apply at https://jobs.lever.co/acme/123", "Recruiter");

        SignalScorer.SignalScores scores = scorer.score(p, ctx);

        assertThat(scores.linkMatch()).isTrue();
        assertThat(scorer.verdict(scores)).isEqualTo(RecruiterPostVerdict.HIGH);
    }

    @Test
    @DisplayName("Company + title + recruiter author yields HIGH")
    void companyTitleAndRecruiterAuthorYieldsHigh() {
        JobContextResolver.JobContext ctx = ctx("Java Engineer", "Acme", null);
        SignalScorer.CandidatePost p = post("Acme is hiring a Java Engineer", "Recruiter at Acme");

        SignalScorer.SignalScores scores = scorer.score(p, ctx);

        assertThat(scores.companyNameMatch()).isTrue();
        assertThat(scores.fuzzyTitleMatch()).isTrue();
        assertThat(scores.authorRole()).isEqualTo(AuthorRole.RECRUITER);
        assertThat(scorer.verdict(scores)).isEqualTo(RecruiterPostVerdict.HIGH);
    }

    @Test
    @DisplayName("Company + title without a known author role yields MEDIUM")
    void companyAndTitleOnlyYieldsMedium() {
        JobContextResolver.JobContext ctx = ctx("Java Engineer", "Acme", null);
        SignalScorer.CandidatePost p = post("Acme is hiring a Java Engineer", "Engineer");

        SignalScorer.SignalScores scores = scorer.score(p, ctx);

        assertThat(scores.companyNameMatch()).isTrue();
        assertThat(scores.fuzzyTitleMatch()).isTrue();
        assertThat(scores.authorRole()).isEqualTo(AuthorRole.UNKNOWN);
        assertThat(scorer.verdict(scores)).isEqualTo(RecruiterPostVerdict.MEDIUM);
    }

    @Test
    @DisplayName("Company-only match yields UNCERTAIN")
    void companyOnlyYieldsUncertain() {
        JobContextResolver.JobContext ctx = ctx("Java Engineer", "Acme", null);
        SignalScorer.CandidatePost p = post("Acme is a great place to work", "Engineer");

        SignalScorer.SignalScores scores = scorer.score(p, ctx);

        assertThat(scores.companyNameMatch()).isTrue();
        assertThat(scores.fuzzyTitleMatch()).isFalse();
        assertThat(scorer.verdict(scores)).isEqualTo(RecruiterPostVerdict.UNCERTAIN);
    }

    @Test
    @DisplayName("Title-only match yields UNCERTAIN")
    void titleOnlyYieldsUncertain() {
        JobContextResolver.JobContext ctx = ctx("Java Engineer", "Acme", null);
        SignalScorer.CandidatePost p = post("Looking for a Java Engineer role", "Engineer");

        SignalScorer.SignalScores scores = scorer.score(p, ctx);

        assertThat(scores.companyNameMatch()).isFalse();
        assertThat(scores.fuzzyTitleMatch()).isTrue();
        assertThat(scorer.verdict(scores)).isEqualTo(RecruiterPostVerdict.UNCERTAIN);
    }

    @Test
    @DisplayName("Company legal suffix is normalized before matching")
    void companyLegalSuffixNormalized() {
        JobContextResolver.JobContext ctx = ctx("Java Engineer", "Acme GmbH", null);
        SignalScorer.CandidatePost p = post("Acme is hiring", "Engineer");

        SignalScorer.SignalScores scores = scorer.score(p, ctx);

        assertThat(scores.companyNameMatch()).isTrue();
    }

    @Test
    @DisplayName("Fuzzy title matches on 2 significant tokens")
    void fuzzyTitleMatchesOnTwoSignificantTokens() {
        JobContextResolver.JobContext ctx = ctx("Java Spring Developer", "Acme", null);
        SignalScorer.CandidatePost p = post("We use Java and Spring every day", "Engineer");

        SignalScorer.SignalScores scores = scorer.score(p, ctx);

        assertThat(scores.fuzzyTitleMatch()).isTrue();
    }

    @Test
    @DisplayName("Role words (senior/engineer/software) are significant for title matching")
    void roleWordsAreSignificantForTitleMatching() {
        JobContextResolver.JobContext ctx = ctx("Senior Software Engineer (m/f/d)", "Flix", null);
        SignalScorer.CandidatePost p = post("Senior Software Engineer at Flix — Berlin, Germany", "GetAJob.ai");

        SignalScorer.SignalScores scores = scorer.score(p, ctx);

        assertThat(scores.fuzzyTitleMatch()).isTrue();
        assertThat(scores.companyNameMatch()).isTrue();
    }

    @Test
    @DisplayName("Recency: relative dates within 30 days match, unparseable dates are not penalized")
    void recencyParsing() {
        JobContextResolver.JobContext ctx = ctx("Java Engineer", "Acme", null);

        assertThat(scorer.score(post("x", "Engineer"), ctx).recencyMatch()).isTrue(); // "2 days ago"
        assertThat(scorer.score(new SignalScorer.CandidatePost("u", "a", "b", "c", "x", "gibberish"), ctx).recencyMatch()).isTrue();
        assertThat(scorer.score(new SignalScorer.CandidatePost("u", "a", "b", "c", "x", null), ctx).recencyMatch()).isTrue();
        assertThat(scorer.score(new SignalScorer.CandidatePost("u", "a", "b", "c", "x", "60 days ago"), ctx).recencyMatch()).isFalse();
    }
}