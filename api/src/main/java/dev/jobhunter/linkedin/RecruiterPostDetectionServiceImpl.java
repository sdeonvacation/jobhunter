package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.model.Company;
import dev.jobhunter.people.model.enums.ContactDiscoverySource;
import dev.jobhunter.repository.CompanyRepository;
import dev.jobhunter.repository.JobPostingRepository;
import dev.jobhunter.repository.OutreachContactRepository;
import dev.jobhunter.repository.RecruiterPostCheckRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class RecruiterPostDetectionServiceImpl implements RecruiterPostDetectionService {

    private static final int SNIPPET_MAX_LENGTH = 500;

    private static final List<String> LEGAL_SUFFIXES = List.of(
            "gmbh", "ag", "inc", "ltd", "llc", "co", "corp", "sarl", "bv", "se",
            "plc", "limited", "ug", "gmbh & co. kg"
    );

    private final RecruiterPostCheckRepository recruiterPostCheckRepository;
    private final JobContextResolver jobContextResolver;
    private final PostSearchService postSearchService;
    private final RecruiterPostAiTiebreaker tiebreaker;
    private final OutreachContactRepository outreachContactRepository;
    private final CompanyRepository companyRepository;
    private final JobPostingRepository jobPostingRepository;
    private final LinkedInMcpProperties mcpProperties;
    private final ObjectMapper objectMapper;
    private final SignalScorer signalScorer = new SignalScorerImpl();

    public RecruiterPostDetectionServiceImpl(RecruiterPostCheckRepository recruiterPostCheckRepository,
                                             JobContextResolver jobContextResolver,
                                             PostSearchService postSearchService,
                                             RecruiterPostAiTiebreaker tiebreaker,
                                             OutreachContactRepository outreachContactRepository,
                                             CompanyRepository companyRepository,
                                             JobPostingRepository jobPostingRepository,
                                             LinkedInMcpProperties mcpProperties,
                                             ObjectMapper objectMapper) {
        this.recruiterPostCheckRepository = recruiterPostCheckRepository;
        this.jobContextResolver = jobContextResolver;
        this.postSearchService = postSearchService;
        this.tiebreaker = tiebreaker;
        this.outreachContactRepository = outreachContactRepository;
        this.companyRepository = companyRepository;
        this.jobPostingRepository = jobPostingRepository;
        this.mcpProperties = mcpProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public RecruiterPostCheckResult checkRecruiterPost(String jobUrl, boolean force) {
        if (!force) {
            Optional<RecruiterPostCheck> cached = recruiterPostCheckRepository
                    .findByJobUrlAndExpiresAtAfter(jobUrl, LocalDateTime.now());
            if (cached.isPresent()) {
                log.debug("Cache hit for recruiter post check: {}", jobUrl);
                return deserializeResult(cached.get());
            }
        }

        LinkedInMcpProperties.RecruiterPostCheckConfig config = mcpProperties.recruiterPostCheck();
        CallBudget budget = new CallBudget(config.maxCalls());
        LocalDateTime now = LocalDateTime.now();

        Optional<JobContextResolver.JobContext> ctxOpt = jobContextResolver.resolve(jobUrl, budget);
        if (ctxOpt.isEmpty()) {
            return persistAndReturn(jobUrl, RecruiterPostVerdict.UNRESOLVED, 0.0, List.of(), null,
                    budget.used(), now, config);
        }
        JobContextResolver.JobContext ctx = ctxOpt.get();

        List<SignalScorer.CandidatePost> candidates =
                postSearchService.searchCandidates(ctx, budget, false, config.recencyWindow());
        if (candidates.isEmpty()) {
            return persistAndReturn(jobUrl, RecruiterPostVerdict.NOT_FOUND, 0.0, List.of(), null,
                    budget.used(), now, config);
        }

        List<ScoredCandidate> scored = new ArrayList<>();
        for (SignalScorer.CandidatePost post : candidates) {
            SignalScorer.SignalScores scores = signalScorer.score(post, ctx);
            scored.add(new ScoredCandidate(post, signalScorer.verdict(scores), scores));
        }

        if (config.aiVerificationEnabled()) {
            for (ScoredCandidate sc : scored) {
                if (sc.verdict == RecruiterPostVerdict.MEDIUM || sc.verdict == RecruiterPostVerdict.UNCERTAIN) {
                    RecruiterPostAiTiebreaker.AiVerdict ai = tiebreaker.classify(sc.post, ctx);
                    if (ai == RecruiterPostAiTiebreaker.AiVerdict.YES && sc.verdict == RecruiterPostVerdict.UNCERTAIN) {
                        sc.verdict = RecruiterPostVerdict.MEDIUM;
                    } else if (ai == RecruiterPostAiTiebreaker.AiVerdict.NO && sc.verdict == RecruiterPostVerdict.MEDIUM) {
                        sc.verdict = RecruiterPostVerdict.UNCERTAIN;
                    }
                }
            }
        }

        ScoredCandidate best = null;
        for (ScoredCandidate sc : scored) {
            if (sc.verdict == RecruiterPostVerdict.HIGH) {
                best = sc;
                break;
            }
        }
        if (best == null) {
            for (ScoredCandidate sc : scored) {
                if (sc.verdict == RecruiterPostVerdict.MEDIUM) {
                    best = sc;
                    break;
                }
            }
        }

        RecruiterPostVerdict verdict;
        double confidence;
        List<MatchedPost> matchedPosts;
        UUID contactId = null;

        if (best != null) {
            verdict = best.verdict;
            confidence = verdict == RecruiterPostVerdict.HIGH ? 0.9 : 0.6;
            matchedPosts = List.of(toMatchedPost(best.post));
            contactId = createOrLinkContact(best.post, ctx, jobUrl);
        } else {
            verdict = RecruiterPostVerdict.UNCERTAIN;
            confidence = 0.3;
            matchedPosts = scored.stream().map(sc -> toMatchedPost(sc.post)).toList();
        }

        return persistAndReturn(jobUrl, verdict, confidence, matchedPosts, contactId, budget.used(), now, config);
    }

    @Override
    public List<RecruiterPostCheckResult> readCached(List<String> jobUrls) {
        if (jobUrls == null || jobUrls.isEmpty()) {
            return List.of();
        }
        List<RecruiterPostCheck> checks = recruiterPostCheckRepository
                .findByJobUrlInAndExpiresAtAfter(jobUrls, LocalDateTime.now());
        return checks.stream().map(this::deserializeResult).toList();
    }

    private UUID createOrLinkContact(SignalScorer.CandidatePost post, JobContextResolver.JobContext ctx, String jobUrl) {
        String authorLinkedinUrl = post.authorLinkedinUrl();
        if (authorLinkedinUrl == null || authorLinkedinUrl.isBlank()) {
            return null;
        }
        try {
            Optional<OutreachContact> existing = outreachContactRepository.findByLinkedinUrl(authorLinkedinUrl);
            UUID contactId;
            if (existing.isPresent()) {
                contactId = existing.get().getId();
            } else {
                Company company = null;
                if (ctx.company() != null && !ctx.company().isBlank()) {
                    company = companyRepository.findByNormalizedName(normalizeCompany(ctx.company())).orElse(null);
                }
                OutreachContact contact = OutreachContact.builder()
                        .personName(post.authorName())
                        .title(post.authorTitle())
                        .linkedinUrl(authorLinkedinUrl)
                        .notes("Recruiter post: " + post.postUrl() + " | "
                                + truncate(post.snippet(), SNIPPET_MAX_LENGTH) + " | job: " + jobUrl)
                        .discoveredVia(ContactDiscoverySource.RECRUITER_POST)
                        .company(company)
                        .build();
                contactId = outreachContactRepository.save(contact).getId();
            }
            if (ctx.jobPostingId() != null) {
                outreachContactRepository.linkContactToJob(ctx.jobPostingId(), contactId);
                jobPostingRepository.findById(ctx.jobPostingId()).ifPresent(job -> {
                    job.setPosterContactId(contactId);
                    job.setPosterName(post.authorName());
                    job.setPosterLinkedinUrl(authorLinkedinUrl);
                    jobPostingRepository.save(job);
                });
            }
            return contactId;
        } catch (Exception e) {
            log.warn("Failed to create/link contact for recruiter post {}: {}", post.postUrl(), e.getMessage());
            return null;
        }
    }

    private RecruiterPostCheckResult persistAndReturn(String jobUrl, RecruiterPostVerdict verdict, double confidence,
                                                      List<MatchedPost> matchedPosts, UUID contactId, int callsUsed,
                                                      LocalDateTime now,
                                                      LinkedInMcpProperties.RecruiterPostCheckConfig config) {
        // Upsert: a re-check (force=true) must update the existing row, not collide with it.
        RecruiterPostCheck check = recruiterPostCheckRepository.findByJobUrl(jobUrl)
                .orElseGet(() -> RecruiterPostCheck.builder().jobUrl(jobUrl).build());
        check.setVerdict(verdict);
        check.setConfidence(confidence);
        check.setResultData(serializeResult(verdict, confidence, matchedPosts, contactId, callsUsed));
        check.setCheckedAt(now);
        check.setExpiresAt(now.plusDays(config.ttlDays()));
        recruiterPostCheckRepository.save(check);
        log.info("Recruiter post check for {}: verdict={}, confidence={}, callsUsed={}",
                jobUrl, verdict, confidence, callsUsed);
        return new RecruiterPostCheckResult(jobUrl, verdict, confidence, matchedPosts, contactId, callsUsed);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> serializeResult(RecruiterPostVerdict verdict, double confidence,
                                                List<MatchedPost> matchedPosts, UUID contactId, int callsUsed) {
        SerializedResult serialized = new SerializedResult(verdict, confidence, matchedPosts, contactId, callsUsed);
        return objectMapper.convertValue(serialized, Map.class);
    }

    private RecruiterPostCheckResult deserializeResult(RecruiterPostCheck check) {
        RecruiterPostVerdict verdict = check.getVerdict();
        double confidence = check.getConfidence();
        List<MatchedPost> matchedPosts = List.of();
        UUID contactId = null;
        int callsUsed = 0;
        Map<String, Object> data = check.getResultData();
        if (data != null) {
            try {
                SerializedResult serialized = objectMapper.convertValue(data, SerializedResult.class);
                if (serialized.verdict() != null) {
                    verdict = serialized.verdict();
                }
                confidence = serialized.confidence();
                if (serialized.matchedPosts() != null) {
                    matchedPosts = serialized.matchedPosts();
                }
                contactId = serialized.contactId();
                callsUsed = serialized.callsUsed();
            } catch (Exception e) {
                log.warn("Failed to deserialize recruiter post check result for {}: {}",
                        check.getJobUrl(), e.getMessage());
            }
        }
        return new RecruiterPostCheckResult(check.getJobUrl(), verdict, confidence, matchedPosts, contactId, callsUsed);
    }

    private MatchedPost toMatchedPost(SignalScorer.CandidatePost post) {
        return new MatchedPost(post.postUrl(), post.authorName(), post.authorTitle(),
                post.authorLinkedinUrl(), post.snippet(), post.postedAt());
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String normalizeCompany(String company) {
        String normalized = normalize(company);
        List<String> suffixes = LEGAL_SUFFIXES.stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toList();
        for (String suffix : suffixes) {
            normalized = normalized.replaceAll("\\s+" + Pattern.quote(normalize(suffix)) + "$", "");
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

    private record SerializedResult(
            RecruiterPostVerdict verdict,
            double confidence,
            List<MatchedPost> matchedPosts,
            UUID contactId,
            int callsUsed
    ) {}

    private static final class ScoredCandidate {
        final SignalScorer.CandidatePost post;
        RecruiterPostVerdict verdict;
        final SignalScorer.SignalScores scores;

        ScoredCandidate(SignalScorer.CandidatePost post, RecruiterPostVerdict verdict,
                        SignalScorer.SignalScores scores) {
            this.post = post;
            this.verdict = verdict;
            this.scores = scores;
        }
    }
}