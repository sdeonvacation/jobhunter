package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jobhunter.model.enums.AtsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostSearchServiceImplTest {

    @Mock
    private HttpMcpClient httpMcpClient;
    @Mock
    private LinkedInRateLimiter rateLimiter;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PostSearchServiceImpl service;

    private JobContextResolver.JobContext ctx;

    @BeforeEach
    void setUp() {
        service = new PostSearchServiceImpl(httpMcpClient, rateLimiter, objectMapper);
        ctx = new JobContextResolver.JobContext("Java Engineer", "Acme", "Berlin", null,
                "https://jobs.lever.co/acme/123", AtsType.LEVER, null, null);
    }

    @Test
    @DisplayName("Slim mode issues exactly one search_posts call with company + title keywords")
    void slimModeIssuesSingleCall() throws Exception {
        JsonNode response = objectMapper.readTree(
                "{\"posts\":[{\"text\":\"We are hiring\",\"url\":\"https://www.linkedin.com/posts/1\"}]}");
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_posts"), anyMap())).thenReturn(response);

        List<SignalScorer.CandidatePost> posts = service.searchCandidates(ctx, new CallBudget(6), true, null);

        assertThat(posts).hasSize(1);
        verify(httpMcpClient, times(1)).callTool(eq("search_posts"),
                argThat(m -> "\"Acme\" \"Java Engineer\"".equals(m.get("keywords"))
                        && !m.containsKey("date_posted"))); // "all" default = no date filter
    }

    @Test
    @DisplayName("Explicit recency window is passed as date_posted")
    void explicitRecencyWindowPassedAsDatePosted() throws Exception {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_posts"), anyMap())).thenReturn(objectMapper.readTree("{\"posts\":[]}"));

        service.searchCandidates(ctx, new CallBudget(6), true, "past-week");

        verify(httpMcpClient).callTool(eq("search_posts"),
                argThat(m -> "past-week".equals(m.get("date_posted"))));
    }

    @Test
    @DisplayName("Full mode issues four search_posts calls with distinct keyword variants")
    void fullModeIssuesFourCalls() throws Exception {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_posts"), anyMap())).thenReturn(objectMapper.readTree("{\"posts\":[]}"));

        List<SignalScorer.CandidatePost> posts = service.searchCandidates(ctx, new CallBudget(6), false, "past-week");

        assertThat(posts).isEmpty();
        verify(httpMcpClient, times(4)).callTool(eq("search_posts"), anyMap());
        verify(httpMcpClient).callTool(eq("search_posts"),
                argThat(m -> "\"Acme\" \"Java Engineer\"".equals(m.get("keywords"))));
        verify(httpMcpClient).callTool(eq("search_posts"),
                argThat(m -> "Acme hiring Java".equals(m.get("keywords"))));
        verify(httpMcpClient).callTool(eq("search_posts"),
                argThat(m -> "Acme hiring".equals(m.get("keywords"))));
        verify(httpMcpClient).callTool(eq("search_posts"),
                argThat(m -> "Java Engineer".equals(m.get("keywords"))));
        verify(httpMcpClient, atLeastOnce()).callTool(eq("search_posts"),
                argThat(m -> "past-week".equals(m.get("date_posted"))));
    }

    @Test
    @DisplayName("Budget exhaustion stops further search calls")
    void budgetExhaustionStopsCalls() throws Exception {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_posts"), anyMap())).thenReturn(objectMapper.readTree("{\"posts\":[]}"));

        List<SignalScorer.CandidatePost> posts = service.searchCandidates(ctx, new CallBudget(1), false, null);

        assertThat(posts).isEmpty();
        verify(httpMcpClient, times(1)).callTool(eq("search_posts"), anyMap());
    }

    @Test
    @DisplayName("Posts are deduplicated by postUrl across variants")
    void dedupByPostUrl() throws Exception {
        JsonNode first = objectMapper.readTree(
                "{\"posts\":[{\"text\":\"first\",\"url\":\"https://www.linkedin.com/posts/1\"}]}");
        JsonNode second = objectMapper.readTree(
                "{\"posts\":[{\"text\":\"second\",\"url\":\"https://www.linkedin.com/posts/1\"}]}");
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_posts"), anyMap())).thenReturn(first, second);

        List<SignalScorer.CandidatePost> posts = service.searchCandidates(ctx, new CallBudget(6), false, null);

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).snippet()).isEqualTo("second"); // last occurrence wins
    }

    @Test
    @DisplayName("structuredContent.references.posts shape is parsed into CandidatePost")
    void parsesStructuredContentShape() throws Exception {
        JsonNode response = objectMapper.readTree(
                "{\"structuredContent\":{\"references\":{\"posts\":[{\"text\":\"We are hiring\","
                        + "\"url\":\"https://www.linkedin.com/posts/1\",\"author_name\":\"Jane\","
                        + "\"author_title\":\"Recruiter\",\"author_url\":\"https://linkedin.com/in/jane\","
                        + "\"date\":\"2 days ago\"}]}}}");
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_posts"), anyMap())).thenReturn(response);

        List<SignalScorer.CandidatePost> posts = service.searchCandidates(ctx, new CallBudget(6), true, null);

        assertThat(posts).hasSize(1);
        SignalScorer.CandidatePost p = posts.get(0);
        assertThat(p.postUrl()).isEqualTo("https://www.linkedin.com/posts/1");
        assertThat(p.authorName()).isEqualTo("Jane");
        assertThat(p.authorTitle()).isEqualTo("Recruiter");
        assertThat(p.authorLinkedinUrl()).isEqualTo("https://linkedin.com/in/jane");
        assertThat(p.snippet()).isEqualTo("We are hiring");
        assertThat(p.postedAt()).isEqualTo("2 days ago");
    }

    @Test
    @DisplayName("content[0].text containing JSON is parsed")
    void parsesContentArrayShape() {
        ObjectNode inner = objectMapper.createObjectNode();
        inner.put("text", "{\"posts\":[{\"text\":\"nested\",\"url\":\"https://www.linkedin.com/posts/9\"}]}");
        ArrayNode contentArray = objectMapper.createArrayNode();
        contentArray.add(inner);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("content", contentArray);

        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_posts"), anyMap())).thenReturn(response);

        List<SignalScorer.CandidatePost> posts = service.searchCandidates(ctx, new CallBudget(6), true, null);

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).snippet()).isEqualTo("nested");
    }

    @Test
    @DisplayName("Real search_posts shape (sections.search_results + references) is parsed into candidates")
    void parsesRealSearchPostsShape() throws Exception {
        JsonNode response = objectMapper.readTree(
                "{\"url\":\"https://www.linkedin.com/search/content?keywords=Acme\","
                        + "\"sections\":{\"search_results\":\"Jane Doe — Talent Acquisition at Acme\\nWe are hiring a Java Engineer at Acme!\"},"
                        + "\"references\":{\"search_results\":["
                        + "{\"kind\":\"person\",\"url\":\"https://www.linkedin.com/in/jane-doe\",\"text\":\"Jane Doe\"},"
                        + "{\"kind\":\"company\",\"url\":\"https://www.linkedin.com/company/acme\",\"text\":\"Acme\"}"
                        + "]}}");
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_posts"), anyMap())).thenReturn(response);

        List<SignalScorer.CandidatePost> posts = service.searchCandidates(ctx, new CallBudget(6), true, null);

        assertThat(posts).hasSize(1);
        SignalScorer.CandidatePost p = posts.get(0);
        assertThat(p.authorName()).isEqualTo("Jane Doe");
        assertThat(p.authorLinkedinUrl()).isEqualTo("https://www.linkedin.com/in/jane-doe");
        assertThat(p.authorTitle()).isEqualTo("Talent Acquisition at Acme"); // headline extracted from raw text
        assertThat(p.postUrl()).isNull(); // no per-post permalinks
        assertThat(p.snippet()).contains("We are hiring a Java Engineer at Acme!");
    }

    @Test
    @DisplayName("Real search_posts shape with no person references falls back to a single text candidate")
    void parsesRealSearchPostsShapeWithoutPersons() throws Exception {
        JsonNode response = objectMapper.readTree(
                "{\"sections\":{\"search_results\":\"We are hiring a Java Engineer at Acme!\"},"
                        + "\"references\":{\"search_results\":["
                        + "{\"kind\":\"company\",\"url\":\"https://www.linkedin.com/company/acme\",\"text\":\"Acme\"}"
                        + "]}}");
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_posts"), anyMap())).thenReturn(response);

        List<SignalScorer.CandidatePost> posts = service.searchCandidates(ctx, new CallBudget(6), true, null);

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).authorName()).isNull();
        assertThat(posts.get(0).snippet()).contains("Java Engineer");
    }

    @Test
    @DisplayName("content[0].text wrapping the real search_posts shape is parsed")
    void parsesContentWrappedRealShape() throws Exception {
        // The sidecar wraps results as content[0].text containing a JSON string with
        // sections.search_results + references.search_results.
        JsonNode response = objectMapper.readTree(
                "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"url\\\":\\\"https://www.linkedin.com/search/results/content/\\\","
                        + "\\\"sections\\\":{\\\"search_results\\\":\\\"GetAJob.ai\\\\n2w • \\\\nSenior Software Engineer at Flix — Berlin, Germany\\\\nApply: https://lnkd.in/eQxZZ9_e\\\"},"
                        + "\\\"references\\\":{\\\"search_results\\\":["
                        + "{\\\"kind\\\":\\\"company\\\",\\\"url\\\":\\\"https://www.linkedin.com/company/getajob-ai\\\",\\\"text\\\":\\\"GetAJob.ai\\\"}"
                        + "]}}\"}]}");
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_posts"), anyMap())).thenReturn(response);

        List<SignalScorer.CandidatePost> posts = service.searchCandidates(ctx, new CallBudget(6), true, null);

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).snippet()).contains("Senior Software Engineer at Flix");
    }

    @Test
    @DisplayName("Multi-post raw text is split into per-post candidates")
    void splitsMultiPostRawText() throws Exception {
        JsonNode response = objectMapper.readTree(
                "{\"sections\":{\"search_results\":\"Feed post\\n\\nGetAJob.ai\\n\\n2w • \\n\\nSenior Software Engineer at Flix — Berlin, Germany\\n\\nApply: https://lnkd.in/eQxZZ9_e\\n\\nFeed post\\n\\nTravelCareers.ai\\n\\n3d • \\n\\n#Berlin #Hiring\\nSenior (iOS) Software Engineer — Navan\\n\\nApply directly: https://lnkd.in/ggduMWWT\"}}");
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_posts"), anyMap())).thenReturn(response);

        List<SignalScorer.CandidatePost> posts = service.searchCandidates(ctx, new CallBudget(6), true, null);

        assertThat(posts).hasSize(2);
        assertThat(posts.get(0).authorName()).isEqualTo("GetAJob.ai");
        assertThat(posts.get(0).snippet()).contains("Senior Software Engineer at Flix");
        assertThat(posts.get(1).authorName()).isEqualTo("TravelCareers.ai");
        assertThat(posts.get(1).snippet()).contains("Navan");
    }

    @Test
    @DisplayName("German gender suffix (m/f/d) is stripped from search queries")
    void stripsGenderSuffixFromQueries() throws Exception {
        JobContextResolver.JobContext germanCtx = new JobContextResolver.JobContext(
                "Senior Software Engineer (m/f/d)", "Flix", "Berlin", null,
                "https://flix.careers/job/8545023002?gh_jid=8545023002", AtsType.GREENHOUSE, null, null);
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_posts"), anyMap())).thenReturn(objectMapper.readTree("{\"posts\":[]}"));

        service.searchCandidates(germanCtx, new CallBudget(6), false, "past-month");

        verify(httpMcpClient).callTool(eq("search_posts"),
                argThat(m -> "\"Flix\" \"Senior Software Engineer\"".equals(m.get("keywords"))));
        verify(httpMcpClient).callTool(eq("search_posts"),
                argThat(m -> "Flix hiring Senior Software Engineer".equals(m.get("keywords"))));
    }

    @Test
    @DisplayName("Headline on a separate line (name + degree + headline) is extracted")
    void extractsHeadlineFromSeparateLine() throws Exception {
        JsonNode response = objectMapper.readTree(
                "{\"sections\":{\"search_results\":\"Feed post\\n\\nYevheniia Perederii\\n\\n• 3rd+\\n\\nTechnical Recruiter at FLIX\\n\\n3mo • \\n\\nFollow\\n\\nFLiX is hiring!\\n\\nSenior Software Engineer at Flix\"}}");
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_posts"), anyMap())).thenReturn(response);

        List<SignalScorer.CandidatePost> posts = service.searchCandidates(ctx, new CallBudget(6), true, null);

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).authorName()).isEqualTo("Yevheniia Perederii");
        assertThat(posts.get(0).authorTitle()).isEqualTo("Technical Recruiter at FLIX");
    }

    @Test
    @DisplayName("Rate limit rejection prevents any callTool invocation")
    void rateLimitRejectionSkipsCalls() {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(false);

        List<SignalScorer.CandidatePost> posts = service.searchCandidates(ctx, new CallBudget(6), true, null);

        assertThat(posts).isEmpty();
        verify(httpMcpClient, never()).callTool(anyString(), anyMap());
    }

    @Test
    @DisplayName("McpClientException yields an empty result after consecutive failures")
    void mcpClientExceptionYieldsEmpty() {
        when(rateLimiter.acquire(ToolCategory.SEARCH)).thenReturn(true);
        when(httpMcpClient.callTool(eq("search_posts"), anyMap())).thenThrow(new McpClientException("boom"));

        List<SignalScorer.CandidatePost> posts = service.searchCandidates(ctx, new CallBudget(6), false, null);

        assertThat(posts).isEmpty();
        verify(httpMcpClient, times(2)).callTool(eq("search_posts"), anyMap()); // 2 consecutive failures then break
    }

    @Test
    @DisplayName("Null context returns empty without any calls")
    void nullContextReturnsEmpty() {
        assertThat(service.searchCandidates(null, new CallBudget(6), true, null)).isEmpty();
        verifyNoInteractions(httpMcpClient, rateLimiter);
    }

    @Test
    @DisplayName("Slim mode with blank company returns empty without any calls")
    void slimModeWithBlankCompanyReturnsEmpty() {
        JobContextResolver.JobContext noCompany = new JobContextResolver.JobContext(
                "Java Engineer", null, null, null, null, null, null, null);

        assertThat(service.searchCandidates(noCompany, new CallBudget(6), true, null)).isEmpty();
        verifyNoInteractions(httpMcpClient);
    }
}