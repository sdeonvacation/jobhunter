package dev.jobhunter.linkedin;

import java.time.LocalDate;

public interface SignalScorer {

    SignalScores score(CandidatePost post, JobContextResolver.JobContext ctx);

    RecruiterPostVerdict verdict(SignalScores scores);

    record SignalScores(
            boolean linkMatch,          // strongest
            boolean companyNameMatch,
            boolean fuzzyTitleMatch,
            AuthorRole authorRole,
            boolean recencyMatch
    ) {}

    record CandidatePost(
            String postUrl,
            String authorName,
            String authorTitle,
            String authorLinkedinUrl,
            String snippet,
            String postedAt
    ) {}
}
