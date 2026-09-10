package dev.jobhunter.linkedin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Searches LinkedIn posts for candidates referencing a specific job opening.
 * Uses the MCP sidecar's search_posts tool with budget + rate-limit guards.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "linkedin-mcp", name = "enabled", havingValue = "true")
public class PostSearchServiceImpl implements PostSearchService {

    private static final Set<String> STOPWORDS = Set.of(
            "senior", "junior", "lead", "principal", "staff", "mid", "entry", "level",
            "full", "stack", "remote", "based", "engineer", "developer", "software",
            "the", "and", "for", "with", "of", "in", "at", "to", "a", "an", "is", "are",
            "we", "our", "you", "your", "new", "join", "team", "looking", "hiring",
            "role", "position", "job", "opening", "opportunity", "time", "part", "work",
            "company", "about", "this", "that", "have", "has", "will", "can", "must",
            "should", "would", "could", "be", "by", "from", "on", "as", "or"
    );

    private final HttpMcpClient httpMcpClient;
    private final LinkedInRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public PostSearchServiceImpl(HttpMcpClient httpMcpClient,
                                 LinkedInRateLimiter rateLimiter,
                                 ObjectMapper objectMapper) {
        this.httpMcpClient = httpMcpClient;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SignalScorer.CandidatePost> searchCandidates(JobContextResolver.JobContext ctx,
                                                             CallBudget budget,
                                                             boolean slim,
                                                             String recencyWindow) {
        if (ctx == null || budget == null) {
            return List.of();
        }
        List<String> variants = buildQueryVariants(ctx, slim);
        if (variants.isEmpty()) {
            return List.of();
        }

        String recency = (recencyWindow == null || recencyWindow.isBlank()) ? "all" : recencyWindow;

        Map<String, SignalScorer.CandidatePost> deduped = new LinkedHashMap<>();
        int consecutiveFailures = 0;

        for (String variant : variants) {
            if (!budget.trySpend()) {
                log.warn("Call budget exhausted during post search for {} at {}", ctx.title(), ctx.company());
                break;
            }
            if (!rateLimiter.acquire(ToolCategory.SEARCH)) {
                log.warn("LinkedIn rate limit reached for SEARCH during post search for {} at {}", ctx.title(), ctx.company());
                break;
            }

            try {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("keywords", variant);
                // "all" (or blank) = no date filter — recruiter posts can predate the
                // job listing by months; the signal scorer + AI tiebreaker handle relevance.
                if (!"all".equalsIgnoreCase(recency)) {
                    params.put("date_posted", recency);
                }
                // max_pages=1 (5 scrolls) keeps the call under Patchright's 60s
                // request timeout; content search is an infinite scroll.
                params.put("max_pages", 1);
                JsonNode response = httpMcpClient.callTool("search_posts", params);
                List<SignalScorer.CandidatePost> posts = parsePosts(response);
                for (SignalScorer.CandidatePost post : posts) {
                    deduped.put(dedupKey(post), post);
                }
                consecutiveFailures = 0;
            } catch (McpClientException e) {
                log.warn("Post search failed for variant '{}': {}", variant, e.getMessage());
                consecutiveFailures++;
                if (consecutiveFailures >= 2) {
                    break;
                }
            } catch (Exception e) {
                log.warn("Unexpected error during post search for variant '{}': {}", variant, e.getMessage());
                consecutiveFailures++;
                if (consecutiveFailures >= 2) {
                    break;
                }
            }
        }

        return new ArrayList<>(deduped.values());
    }

    private List<String> buildQueryVariants(JobContextResolver.JobContext ctx, boolean slim) {
        String company = ctx.company() == null ? "" : ctx.company().trim();
        String title = cleanTitleForSearch(ctx.title());

        if (slim) {
            if (company.isBlank() || title.isBlank()) {
                return List.of();
            }
            return List.of("\"" + company + "\" \"" + title + "\"");
        }

        List<String> variants = new ArrayList<>();
        if (!company.isBlank() && !title.isBlank()) {
            variants.add("\"" + company + "\" \"" + title + "\"");
        }
        if (!company.isBlank() && !title.isBlank()) {
            String roleKeyword = roleKeyword(title);
            variants.add(company + " hiring " + roleKeyword);
        }
        if (!company.isBlank()) {
            // Broad "X hiring" catches "FLiX is hiring!" posts that never mention the title
            variants.add(company + " hiring");
        }
        if (!title.isBlank()) {
            variants.add(title);
        }
        return variants;
    }

    /**
     * Strips German gender suffixes ("(m/f/d)", "(w/m/d)", "(m/w/d)", "(f/m/d)") and
     * trailing "| tagline" noise from a job title so LinkedIn content search can match
     * posts that never contain those tokens.
     */
    private String cleanTitleForSearch(String title) {
        if (title == null) {
            return "";
        }
        String cleaned = title.replaceAll("(?i)\\([mfw]/[mfw]/[mfwdx]+\\)", " ")
                .replaceAll("\\s*\\|.*$", "")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned;
    }

    private String roleKeyword(String title) {
        String[] tokens = title.split("\\s+");
        for (String token : tokens) {
            String lower = token.toLowerCase(Locale.ROOT);
            // Skip stopwords and tokens with non-alphanumeric chars (e.g. "(m/f/d)", "|", "-")
            if (lower.length() >= 3 && !STOPWORDS.contains(lower) && token.matches("[A-Za-z0-9]+")) {
                return token;
            }
        }
        return title;
    }

    private String dedupKey(SignalScorer.CandidatePost post) {
        if (post.postUrl() != null && !post.postUrl().isBlank()) {
            return post.postUrl();
        }
        if (post.authorLinkedinUrl() != null && !post.authorLinkedinUrl().isBlank()) {
            return post.authorLinkedinUrl();
        }
        if (post.authorName() != null && !post.authorName().isBlank()) {
            return post.authorName();
        }
        String snippet = post.snippet();
        return snippet == null ? String.valueOf(System.identityHashCode(post)) : String.valueOf(snippet.hashCode());
    }

    private List<SignalScorer.CandidatePost> parsePosts(JsonNode response) {
        if (response == null) {
            return List.of();
        }

        // The sidecar wraps results as content[0].text containing a JSON string with the
        // real search_posts shape: {url, sections: {search_results: rawText},
        // references: {search_results: [Reference{kind,url,text}]}} — no per-post permalinks.
        JsonNode contentArray = response.path("content");
        if (contentArray.isArray() && !contentArray.isEmpty()) {
            JsonNode textNode = contentArray.get(0).path("text");
            if (textNode.isTextual()) {
                try {
                    JsonNode parsed = objectMapper.readTree(textNode.asText());
                    List<SignalScorer.CandidatePost> posts = parseSearchPostsShape(parsed);
                    if (!posts.isEmpty()) {
                        return posts;
                    }
                    // Legacy content-wrapped shape: posts under references.posts or posts
                    JsonNode refs = parsed.path("references").path("posts");
                    if (refs.isArray() && !refs.isEmpty()) {
                        return parsePostArray(refs);
                    }
                    JsonNode postsNode = parsed.path("posts");
                    if (postsNode.isArray() && !postsNode.isEmpty()) {
                        return parsePostArray(postsNode);
                    }
                } catch (Exception ignored) {
                    // Not JSON or wrong format, fall through
                }
            }
        }

        // Direct shape (some transports may unwrap the content array)
        List<SignalScorer.CandidatePost> direct = parseSearchPostsShape(response);
        if (!direct.isEmpty()) {
            return direct;
        }

        // structuredContent: posts under references.posts or posts array
        JsonNode structured = response.path("structuredContent");
        if (!structured.isMissingNode()) {
            JsonNode refs = structured.path("references").path("posts");
            if (refs.isArray() && !refs.isEmpty()) {
                return parsePostArray(refs);
            }
            JsonNode posts = structured.path("posts");
            if (posts.isArray() && !posts.isEmpty()) {
                return parsePostArray(posts);
            }
        }

        // Legacy flat arrays under posts/results
        JsonNode postsNode = response.path("posts");
        if (!postsNode.isArray()) {
            postsNode = response.isArray() ? response : response.path("results");
        }
        if (!postsNode.isArray()) {
            return List.of();
        }
        return parsePostArray(postsNode);
    }

    private List<SignalScorer.CandidatePost> parseSearchPostsShape(JsonNode node) {
        String rawText = getTextOrNull(node.path("sections"), "search_results");
        JsonNode refs = node.path("references").path("search_results");
        if (rawText != null || (refs.isArray() && !refs.isEmpty())) {
            return parseSearchPostsResponse(rawText, refs);
        }
        return List.of();
    }

    private List<SignalScorer.CandidatePost> parseSearchPostsResponse(String rawText, JsonNode refs) {
        List<SignalScorer.CandidatePost> posts = new ArrayList<>();
        // The results page renders each post as a "Feed post" block. Split the raw
        // text into per-post segments so signal scoring and the AI tiebreaker can
        // evaluate each post individually instead of the whole page.
        List<String> segments = splitPosts(rawText);
        if (refs != null && refs.isArray()) {
            for (JsonNode ref : refs) {
                String kind = ref.path("kind").asText("");
                String url = ref.path("url").asText("");
                String text = ref.path("text").asText("");
                if ("person".equals(kind) && !text.isBlank()) {
                    String segment = segmentForAuthor(segments, text);
                    String headline = extractHeadline(segment != null ? segment : rawText, text);
                    posts.add(new SignalScorer.CandidatePost(null, text, headline, absoluteLinkedinUrl(url),
                            segment != null ? segment : rawText, null));
                } else if ("company".equals(kind) && !text.isBlank()) {
                    // A company reference is the poster only when its name leads a post
                    // segment (e.g. "DevOpsHunt" posting). Companies merely mentioned in
                    // post bodies (e.g. "Flix" inside a recruiter's post) are not candidates.
                    String segment = segmentAuthoredBy(segments, text);
                    if (segment != null) {
                        String headline = extractHeadline(segment, text);
                        posts.add(new SignalScorer.CandidatePost(null, text, headline, absoluteLinkedinUrl(url),
                                segment, null));
                    }
                } else if (("feed_post".equals(kind) || "article".equals(kind)) && !url.isBlank()) {
                    posts.add(new SignalScorer.CandidatePost(absoluteLinkedinUrl(url), null, null, null,
                            segments.isEmpty() ? rawText : segments.get(0), null));
                }
            }
        }
        if (posts.isEmpty()) {
            // Fallback: one candidate per post segment so text signals still apply
            for (String segment : segments) {
                String author = null;
                int nl = segment.indexOf('\n');
                if (nl > 0) {
                    String first = segment.substring(0, nl).trim();
                    if (!first.isBlank() && first.length() <= 60) {
                        author = first;
                    }
                }
                String headline = author != null ? extractHeadline(segment, author) : null;
                posts.add(new SignalScorer.CandidatePost(null, author, headline, null, segment, null));
            }
        }
        return posts;
    }

    /** Splits the raw results text into per-post segments on "Feed post" markers. */
    private List<String> splitPosts(String rawText) {
        List<String> segments = new ArrayList<>();
        if (rawText == null || rawText.isBlank()) {
            return segments;
        }
        String[] parts = rawText.split("(?i)Feed post");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                segments.add(trimmed);
            }
        }
        return segments;
    }

    /** Returns the segment whose text starts with (or contains) the author name. */
    private String segmentForAuthor(List<String> segments, String authorName) {
        if (segments.isEmpty() || authorName == null || authorName.isBlank()) {
            return null;
        }
        for (String segment : segments) {
            if (segment.startsWith(authorName) || segment.contains(authorName)) {
                return segment;
            }
        }
        return null;
    }

    /** Returns the segment whose first non-blank line is the given author name (the poster). */
    private String segmentAuthoredBy(List<String> segments, String authorName) {
        if (segments.isEmpty() || authorName == null || authorName.isBlank()) {
            return null;
        }
        for (String segment : segments) {
            String firstLine = firstNonBlankLine(segment);
            if (firstLine != null && (firstLine.equals(authorName) || firstLine.startsWith(authorName))) {
                return segment;
            }
        }
        return null;
    }

    private String firstNonBlankLine(String text) {
        if (text == null) {
            return null;
        }
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isBlank()) {
                return trimmed;
            }
        }
        return null;
    }

    /** Prefixes relative LinkedIn paths (e.g. /in/jane, /company/acme) with the site origin. */
    private String absoluteLinkedinUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return "https://www.linkedin.com" + (url.startsWith("/") ? url : "/" + url);
    }

    /**
     * LinkedIn search results render authors as "Name — Headline" or
     * "Name\n\n• 3rd+\n\nHeadline". Extract the headline following the author
     * name so role classification can work.
     */
    private String extractHeadline(String rawText, String authorName) {
        if (rawText == null || authorName == null || authorName.isBlank()) {
            return null;
        }
        int idx = rawText.indexOf(authorName);
        if (idx < 0) {
            return null;
        }
        String after = rawText.substring(idx + authorName.length());
        int sep = after.indexOf("—");
        if (sep < 0) {
            sep = after.indexOf("–");
        }
        if (sep < 0) {
            sep = after.indexOf(" - ");
        }
        if (sep >= 0) {
            String headline = after.substring(sep + 1).trim();
            int nl = headline.indexOf('\n');
            if (nl >= 0) {
                headline = headline.substring(0, nl);
            }
            return cleanHeadline(headline);
        }
        // No dash separator: headline sits on its own line after the author name,
        // skipping connection-degree ("• 3rd+") and action ("Follow"/"Connect") lines.
        String[] lines = after.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (trimmed.matches("•\\s*\\d+(st|nd|rd|th)\\+?")) {
                continue;
            }
            if (trimmed.equalsIgnoreCase("Follow") || trimmed.equalsIgnoreCase("Connect")
                    || trimmed.equalsIgnoreCase("Book an appointment")) {
                continue;
            }
            return cleanHeadline(trimmed);
        }
        return null;
    }

    private String cleanHeadline(String headline) {
        if (headline == null) {
            return null;
        }
        if (headline.length() > 120) {
            headline = headline.substring(0, 120);
        }
        return headline.isBlank() ? null : headline.trim();
    }

    private List<SignalScorer.CandidatePost> parsePostArray(JsonNode postsNode) {
        List<SignalScorer.CandidatePost> posts = new ArrayList<>();
        for (JsonNode post : postsNode) {
            String text = getTextOrNull(post, "text", "content", "snippet", "summary");
            String url = getTextOrNull(post, "url", "post_url", "permalink", "postUrl");
            String authorName = getTextOrNull(post, "author_name", "author.name", "author.full_name");
            String authorTitle = getTextOrNull(post, "author_title", "author.title", "author.headline");
            String authorUrl = getTextOrNull(post, "author_url", "author.url", "author.linkedin_url");
            String date = getTextOrNull(post, "date", "posted_at", "created_at", "date_posted");

            if (text == null && url == null) {
                continue;
            }
            posts.add(new SignalScorer.CandidatePost(url, authorName, authorTitle, authorUrl, text, date));
        }
        return posts;
    }

    private String getTextOrNull(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }
}