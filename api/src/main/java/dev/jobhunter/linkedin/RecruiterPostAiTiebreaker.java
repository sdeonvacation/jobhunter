package dev.jobhunter.linkedin;

public interface RecruiterPostAiTiebreaker {

    /**
     * LLM classification: does this post refer to THIS specific opening?
     * UNKNOWN on provider unavailable/error.
     */
    AiVerdict classify(SignalScorer.CandidatePost post, JobContextResolver.JobContext ctx);

    enum AiVerdict { YES, NO, UNKNOWN }
}
