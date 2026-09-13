package dev.jobhunter.strategy.aggregator;

import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.RawAggregatorJob;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base-behaviour tests for {@link UniversityBoardStrategy}, exercised through a
 * test-only fixture board with a stubbed {@code getHtml} (no network).
 */
class UniversityBoardStrategyTest {

    private static final String BASE = "https://board.test";
    private static final String LISTING_MARKER = "/listing";

    // ─── Test fixture boards ──────────────────────────────────────────────────

    /** Full fixture: scope predicate + null-detail support + call recording. */
    static class FixtureBoard extends UniversityBoardStrategy {

        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<Long> detailFetchTimestamps = new CopyOnWriteArrayList<>();
        private final Function<String, String> handler;

        FixtureBoard(Function<String, String> handler) {
            super(null);
            this.handler = handler;
        }

        @Override
        public String name() {
            return "fixture";
        }

        @Override
        protected String buildListingUrl(String baseUrl, int page) {
            return baseUrl + LISTING_MARKER + "?page=" + page;
        }

        @Override
        protected List<BoardJob> parseListingJobs(String html) {
            if (html != null && html.contains("PARSE_FAIL")) {
                throw new ListingParseException("fixture listing markup drifted");
            }
            return parseFixtureListing(html);
        }

        @Override
        protected boolean matchesScope(BoardJob job) {
            return "in".equals(job.attributes().get("scope"));
        }

        @Override
        protected RawAggregatorJob parseDetail(String html, BoardJob job) {
            if (html != null && html.contains("SKIP")) {
                return null;
            }
            return new RawAggregatorJob(job.externalId(), job.title(), "Company " + job.externalId(),
                    job.location(), html, job.applyUrl(), null, null, null, null, null);
        }

        @Override
        protected String getHtml(String url) {
            requests.add(url);
            if (url.contains(LISTING_MARKER)) {
                return handler.apply(url);
            }
            detailFetchTimestamps.add(System.nanoTime());
            return handler.apply(url);
        }
    }

    /**
     * Second fixture proving the thin-add contract: implements only
     * {@code buildListingUrl}, {@code parseListingJobs} and {@code parseDetail},
     * inheriting the default match-all {@code matchesScope}.
     */
    static class MinimalFixtureBoard extends UniversityBoardStrategy {

        private final Function<String, String> handler;

        MinimalFixtureBoard(Function<String, String> handler) {
            super(null);
            this.handler = handler;
        }

        @Override
        public String name() {
            return "minimal-fixture";
        }

        @Override
        protected String buildListingUrl(String baseUrl, int page) {
            return baseUrl + LISTING_MARKER + "?page=" + page;
        }

        @Override
        protected List<BoardJob> parseListingJobs(String html) {
            return parseFixtureListing(html);
        }

        @Override
        protected RawAggregatorJob parseDetail(String html, BoardJob job) {
            return new RawAggregatorJob(job.externalId(), job.title(), "Company " + job.externalId(),
                    job.location(), html, job.applyUrl(), null, null, null, null, null);
        }

        @Override
        protected String getHtml(String url) {
            return handler.apply(url);
        }
    }

    // ─── Pagination ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("enumerates pages 1..maxPages and never requests beyond the bound")
    void paginatesWithinMaxPages() {
        Map<String, String> pages = Map.of(
                BASE + LISTING_MARKER + "?page=1", listing("1:in"),
                BASE + LISTING_MARKER + "?page=2", listing("2:in"),
                BASE + LISTING_MARKER + "?page=3", listing("3:in"),
                BASE + LISTING_MARKER + "?page=4", listing("4:in"));
        FixtureBoard board = new FixtureBoard(url -> pages.getOrDefault(url, listing()));

        FetchResult result = board.fetch(ctx(100, Map.of("maxPages", "3")));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(3);
        assertThat(board.requests).noneMatch(url -> url.contains("page=4"));
    }

    @Test
    @DisplayName("stops enumerating early when a page yields no new externalIds")
    void stopsWhenPageAddsNoNewIds() {
        Map<String, String> pages = Map.of(
                BASE + LISTING_MARKER + "?page=1", listing("1:in", "2:in"),
                BASE + LISTING_MARKER + "?page=2", listing("1:in", "2:in"),
                BASE + LISTING_MARKER + "?page=3", listing("3:in"));
        FixtureBoard board = new FixtureBoard(url -> pages.getOrDefault(url, listing()));

        FetchResult result = board.fetch(ctx(100, Map.of("maxPages", "5")));

        assertThat(result.jobs()).hasSize(2);
        assertThat(board.requests).noneMatch(url -> url.contains("page=3"));
    }

    @Test
    @DisplayName("dedups records by externalId across pages")
    void dedupsAcrossPages() {
        Map<String, String> pages = Map.of(
                BASE + LISTING_MARKER + "?page=1", listing("1:in", "2:in"),
                BASE + LISTING_MARKER + "?page=2", listing("2:in", "3:in"));
        FixtureBoard board = new FixtureBoard(url -> pages.getOrDefault(url, listing()));

        FetchResult result = board.fetch(ctx(100, Map.of("maxPages", "2")));

        assertThat(result.jobs()).extracting(RawAggregatorJob::externalId)
                .containsExactly("1", "2", "3");
        assertThat(board.requests.stream().filter(u -> u.equals("https://detail/2")).count())
                .isEqualTo(1);
    }

    // ─── Scope predicate ──────────────────────────────────────────────────────

    @Test
    @DisplayName("only in-scope records proceed to the detail fetch")
    void scopeFiltersOutUnmatchedRecords() {
        FixtureBoard board = new FixtureBoard(url -> url.contains(LISTING_MARKER)
                ? listing("1:in", "2:out", "3:in")
                : "<html>ok</html>");

        FetchResult result = board.fetch(ctx(100, Map.of("maxPages", "1")));

        assertThat(result.jobs()).extracting(RawAggregatorJob::externalId)
                .containsExactly("1", "3");
        assertThat(board.requests).noneMatch(url -> url.equals("https://detail/2"));
    }

    @Test
    @DisplayName("thin-add board inherits the match-all default and keeps out-of-scope records")
    void minimalBoardInheritsMatchAllDefault() {
        MinimalFixtureBoard board = new MinimalFixtureBoard(url -> url.contains(LISTING_MARKER)
                ? listing("1:in", "2:out")
                : "<html>ok</html>");

        FetchResult result = board.fetch(ctx(100, Map.of("maxPages", "1")));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).extracting(RawAggregatorJob::externalId)
                .containsExactly("1", "2");
    }

    // ─── Bounds ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bounds candidates by maxScrapePerRun")
    void boundsByMaxScrapePerRun() {
        FixtureBoard board = new FixtureBoard(url -> url.contains(LISTING_MARKER)
                ? listing("1:in", "2:in", "3:in", "4:in")
                : "<html>ok</html>");

        FetchResult result = board.fetch(ctx(100, Map.of("maxPages", "1", "maxScrapePerRun", "2")));

        assertThat(result.jobs()).extracting(RawAggregatorJob::externalId)
                .containsExactly("1", "2");
        assertThat(board.requests).noneMatch(url -> url.equals("https://detail/3"));
    }

    @Test
    @DisplayName("bounds candidates by context.maxResults when it is smaller")
    void boundsByContextMaxResults() {
        FixtureBoard board = new FixtureBoard(url -> url.contains(LISTING_MARKER)
                ? listing("1:in", "2:in", "3:in")
                : "<html>ok</html>");

        FetchResult result = board.fetch(ctx(2, Map.of("maxPages", "1", "maxScrapePerRun", "10")));

        assertThat(result.jobs()).extracting(RawAggregatorJob::externalId)
                .containsExactly("1", "2");
    }

    // ─── Politeness ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("applies delayBetweenMs between detail fetches")
    void appliesDelayBetweenDetailFetches() {
        long delayMs = 60L;
        FixtureBoard board = new FixtureBoard(url -> url.contains(LISTING_MARKER)
                ? listing("1:in", "2:in", "3:in")
                : "<html>ok</html>");

        FetchResult result = board.fetch(ctx(100,
                Map.of("maxPages", "1", "delayBetweenMs", String.valueOf(delayMs))));

        assertThat(result.jobs()).hasSize(3);
        List<Long> timestamps = board.detailFetchTimestamps;
        assertThat(timestamps).hasSize(3);
        long minGapNanos = TimeUnit.MILLISECONDS.toNanos(delayMs);
        assertThat(timestamps.get(1) - timestamps.get(0)).isGreaterThanOrEqualTo(minGapNanos);
        assertThat(timestamps.get(2) - timestamps.get(1)).isGreaterThanOrEqualTo(minGapNanos);
    }

    // ─── Fault tolerance / outcomes ───────────────────────────────────────────

    @Test
    @DisplayName("mid-run transport failure preserves already-collected jobs as partial success")
    void partialSuccessOnMidRunFailure() {
        FixtureBoard board = new FixtureBoard(url -> {
            if (url.contains(LISTING_MARKER)) {
                return listing("1:in", "2:in", "3:in");
            }
            if (url.equals("https://detail/2")) {
                throw WebClientResponseException.create(503, "Service Unavailable", null, null, null);
            }
            return "<html>ok</html>";
        });

        FetchResult result = board.fetch(ctx(100,
                Map.of("maxPages", "1", "maxConsecutiveDetailFailures", "5")));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).extracting(RawAggregatorJob::externalId)
                .containsExactly("1", "3");
    }

    @Test
    @DisplayName("429 on a listing page maps to RATE_LIMITED")
    void rateLimitedOnListing429() {
        FixtureBoard board = new FixtureBoard(url -> {
            throw WebClientResponseException.create(429, "Too Many Requests", null, null, null);
        });

        assertThat(board.fetch(ctx(100, Map.of("maxPages", "1"))).status())
                .isEqualTo(ExtractionStatus.RATE_LIMITED);
    }

    @Test
    @DisplayName("429 on a detail page maps to RATE_LIMITED")
    void rateLimitedOnDetail429() {
        FixtureBoard board = new FixtureBoard(url -> {
            if (url.contains(LISTING_MARKER)) {
                return listing("1:in", "2:in");
            }
            if (url.equals("https://detail/2")) {
                throw WebClientResponseException.create(429, "Too Many Requests", null, null, null);
            }
            return "<html>ok</html>";
        });

        assertThat(board.fetch(ctx(100, Map.of("maxPages", "1"))).status())
                .isEqualTo(ExtractionStatus.RATE_LIMITED);
    }

    @Test
    @DisplayName("5xx before any candidate maps to ERROR")
    void errorOnListingFailureBeforeCandidates() {
        FixtureBoard board = new FixtureBoard(url -> {
            throw WebClientResponseException.create(500, "Internal Server Error", null, null, null);
        });

        FetchResult result = board.fetch(ctx(100, Map.of("maxPages", "1")));

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.jobs()).isEmpty();
    }

    @Test
    @DisplayName("empty enumeration maps to EMPTY")
    void emptyEnumeration() {
        FixtureBoard board = new FixtureBoard(url -> listing());

        assertThat(board.fetch(ctx(100, Map.of("maxPages", "1"))).status())
                .isEqualTo(ExtractionStatus.EMPTY);
    }

    @Test
    @DisplayName("listing parse failure before any job maps to ERROR and stops paging")
    void listingParseFailureMapsToError() {
        FixtureBoard board = new FixtureBoard(url -> url.contains(LISTING_MARKER)
                ? "<html><body>PARSE_FAIL</body></html>"
                : "<html>ok</html>");

        FetchResult result = board.fetch(ctx(100, Map.of("maxPages", "3")));

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.jobs()).isEmpty();
        assertThat(board.requests).hasSize(1); // drifted page 1 must not page on
    }

    @Test
    @DisplayName("listing parse failure after earlier pages yielded jobs maps to partial SUCCESS")
    void listingParseFailureAfterJobsMapsToPartialSuccess() {
        Map<String, String> pages = Map.of(
                BASE + LISTING_MARKER + "?page=1", listing("1:in", "2:in"),
                BASE + LISTING_MARKER + "?page=2", "<html><body>PARSE_FAIL</body></html>");
        FixtureBoard board = new FixtureBoard(url -> pages.getOrDefault(url, "<html>ok</html>"));

        FetchResult result = board.fetch(ctx(100, Map.of("maxPages", "5")));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).extracting(RawAggregatorJob::externalId)
                .containsExactly("1", "2");
        assertThat(board.requests).noneMatch(url -> url.contains("page=3"));
    }

    @Test
    @DisplayName("aborts the detail loop after maxConsecutiveDetailFailures genuine transport failures")
    void abortsAfterConsecutiveDetailTransportFailures() {
        FixtureBoard board = new FixtureBoard(url -> {
            if (url.contains(LISTING_MARKER)) {
                return listing("1:in", "2:in", "3:in");
            }
            throw WebClientResponseException.create(503, "Service Unavailable", null, null, null);
        });

        FetchResult result = board.fetch(ctx(100,
                Map.of("maxPages", "1", "maxConsecutiveDetailFailures", "2")));

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(board.requests.stream().filter(u -> u.startsWith("https://detail/")).count())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a 404 detail skip resets the consecutive-failure counter")
    void detail404ResetsConsecutiveFailureCounter() {
        FixtureBoard board = new FixtureBoard(url -> {
            if (url.contains(LISTING_MARKER)) {
                return listing("1:in", "2:in", "3:in", "4:in");
            }
            if (url.equals("https://detail/2")) {
                throw WebClientResponseException.create(404, "Not Found", null, null, null);
            }
            throw WebClientResponseException.create(503, "Service Unavailable", null, null, null);
        });

        FetchResult result = board.fetch(ctx(100,
                Map.of("maxPages", "1", "maxConsecutiveDetailFailures", "2")));

        // Without the 404 reset the loop would abort after detail/3; the reset pushes the abort to detail/4.
        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(board.requests.stream().filter(u -> u.startsWith("https://detail/")).count())
                .isEqualTo(4);
    }

    @Test
    @DisplayName("a successful detail fetch resets the consecutive-failure counter")
    void detailSuccessResetsConsecutiveFailureCounter() {
        FixtureBoard board = new FixtureBoard(url -> {
            if (url.contains(LISTING_MARKER)) {
                return listing("1:in", "2:in", "3:in", "4:in");
            }
            if (url.equals("https://detail/2")) {
                return "<html>ok</html>";
            }
            throw WebClientResponseException.create(503, "Service Unavailable", null, null, null);
        });

        FetchResult result = board.fetch(ctx(100,
                Map.of("maxPages", "1", "maxConsecutiveDetailFailures", "2")));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).extracting(RawAggregatorJob::externalId).containsExactly("2");
        assertThat(board.requests.stream().filter(u -> u.startsWith("https://detail/")).count())
                .isEqualTo(4);
    }

    @Test
    @DisplayName("parseDetail returning null skips that job and continues the loop")
    void nullDetailSkipsJobAndContinues() {
        FixtureBoard board = new FixtureBoard(url -> {
            if (url.contains(LISTING_MARKER)) {
                return listing("1:in", "2:in");
            }
            return url.equals("https://detail/1") ? "<html>SKIP</html>" : "<html>ok</html>";
        });

        FetchResult result = board.fetch(ctx(100, Map.of("maxPages", "1")));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).extracting(RawAggregatorJob::externalId)
                .containsExactly("2");
    }

    @Test
    @DisplayName("missing detailUrl skips the fetch but still emits via parseDetail(null, job)")
    void missingDetailUrlStillEmits() {
        FixtureBoard board = new FixtureBoard(url -> url.contains(LISTING_MARKER)
                ? listing("1:in:none")
                : "<html>ok</html>");

        FetchResult result = board.fetch(ctx(100, Map.of("maxPages", "1")));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).singleElement().satisfies(job -> {
            assertThat(job.externalId()).isEqualTo("1");
            assertThat(job.applyUrl()).isEqualTo("https://apply/1");
        });
        assertThat(board.requests).noneMatch(url -> url.startsWith("https://detail/"));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static FetchContext ctx(int maxResults, Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>();
        config.put("url", BASE);
        config.put("delayBetweenMs", "0");
        config.putAll(extra);
        return FetchContext.forSearch(null, null, maxResults, 3, config);
    }

    /** Build a listing page; entries are {@code "id:scope[:detail]"} ({@code detail=none} ⇒ null). */
    private static String listing(String... entries) {
        StringBuilder sb = new StringBuilder("<html><body>");
        for (String entry : entries) {
            String[] parts = entry.split(":");
            String id = parts[0];
            String scope = parts.length > 1 ? parts[1] : "in";
            String detail = parts.length > 2 ? parts[2] : "";
            sb.append("<div class=\"job\" data-id=\"").append(id)
                    .append("\" data-scope=\"").append(scope)
                    .append("\" data-detail=\"").append(detail)
                    .append("\"></div>");
        }
        return sb.append("</body></html>").toString();
    }

    private static List<BoardJob> parseFixtureListing(String html) {
        List<BoardJob> jobs = new ArrayList<>();
        for (Element el : Jsoup.parse(html).select("div.job")) {
            String id = el.attr("data-id");
            String detail = el.attr("data-detail");
            String detailUrl;
            if ("none".equals(detail)) {
                detailUrl = null;
            } else if (detail.isBlank()) {
                detailUrl = "https://detail/" + id;
            } else {
                detailUrl = detail;
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("scope", el.attr("data-scope"));
            jobs.add(new BoardJob(id, "Job " + id, "Company " + id, "Berlin",
                    "https://apply/" + id, detailUrl, attributes));
        }
        return jobs;
    }
}
