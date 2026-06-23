package dev.jobhunter.strategy.ats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jobhunter.model.enums.AtsType;
import dev.jobhunter.model.enums.ExtractionStatus;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractAtsStrategyTest {

    private TestStrategy strategy;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        strategy = new TestStrategy();
    }

    // ---- elapsed ----

    @Test
    void elapsed_returnsNonNegativeDuration() {
        Instant start = Instant.now();
        var duration = strategy.elapsed(start);
        assertThat(duration.isNegative()).isFalse();
        assertThat(duration.toMillis()).isGreaterThanOrEqualTo(0);
    }

    // ---- truncate ----

    @Test
    void truncate_null_returnsNull() {
        assertThat(strategy.truncate(null, 10)).isNull();
    }

    @Test
    void truncate_shorterThanMax_returnsOriginal() {
        assertThat(strategy.truncate("abc", 5)).isEqualTo("abc");
    }

    @Test
    void truncate_exactlyMax_returnsOriginal() {
        assertThat(strategy.truncate("abc", 3)).isEqualTo("abc");
    }

    @Test
    void truncate_longerThanMax_returnsTruncated() {
        assertThat(strategy.truncate("abc", 2)).isEqualTo("ab");
    }

    // ---- stripHtml ----

    @Test
    void stripHtml_null_returnsEmpty() {
        assertThat(strategy.stripHtml(null)).isEqualTo("");
    }

    @Test
    void stripHtml_blank_returnsEmpty() {
        assertThat(strategy.stripHtml("   ")).isEqualTo("");
    }

    @Test
    void stripHtml_withTags_removesTagsAndTrims() {
        assertThat(strategy.stripHtml("<p>text</p>")).isEqualTo("text");
    }

    @Test
    void stripHtml_withNestedTags_extractsPlainText() {
        assertThat(strategy.stripHtml("<div><p>hello <strong>world</strong></p></div>"))
                .isEqualTo("hello world");
    }

    @Test
    void stripHtml_decodesHtmlEntities() {
        assertThat(strategy.stripHtml("a &amp; b &lt;c&gt;")).isEqualTo("a & b <c>");
    }

    @Test
    void stripHtml_collapsesWhitespace() {
        assertThat(strategy.stripHtml("<p>  foo   bar  </p>")).isEqualTo("foo bar");
    }

    // ---- parseIsoDate ----

    @Test
    void parseIsoDate_null_returnsNull() {
        assertThat(strategy.parseIsoDate(null)).isNull();
    }

    @Test
    void parseIsoDate_blank_returnsNull() {
        assertThat(strategy.parseIsoDate("")).isNull();
    }

    @Test
    void parseIsoDate_invalid_returnsNull() {
        assertThat(strategy.parseIsoDate("not-a-date")).isNull();
    }

    @Test
    void parseIsoDate_validZonedDateTime_returnsParsedDate() {
        LocalDate result = strategy.parseIsoDate("2024-03-15T10:30:00Z");
        assertThat(result).isEqualTo(LocalDate.of(2024, 3, 15));
    }

    @Test
    void parseIsoDate_validLocalDate_returnsParsedDate() {
        LocalDate result = strategy.parseIsoDate("2024-03-15");
        assertThat(result).isEqualTo(LocalDate.of(2024, 3, 15));
    }

    @Test
    void parseIsoDate_validWithOffset_returnsParsedDate() {
        LocalDate result = strategy.parseIsoDate("2024-03-15T10:30:00+02:00");
        assertThat(result).isEqualTo(LocalDate.of(2024, 3, 15));
    }

    // ---- textOrNull ----

    @Test
    void textOrNull_nullNode_returnsNull() {
        assertThat(strategy.textOrNull(null, "key")).isNull();
    }

    @Test
    void textOrNull_missingPath_returnsNull() throws Exception {
        JsonNode node = objectMapper.readTree("{\"other\":\"value\"}");
        assertThat(strategy.textOrNull(node, "key")).isNull();
    }

    @Test
    void textOrNull_blankValue_returnsNull() throws Exception {
        JsonNode node = objectMapper.readTree("{\"key\":\"   \"}");
        assertThat(strategy.textOrNull(node, "key")).isNull();
    }

    @Test
    void textOrNull_presentValue_returnsValue() throws Exception {
        JsonNode node = objectMapper.readTree("{\"key\":\"hello\"}");
        assertThat(strategy.textOrNull(node, "key")).isEqualTo("hello");
    }

    // ---- mapArray ----

    @Test
    void mapArray_nullNode_returnsEmptyList() {
        assertThat(strategy.mapArray(null, n -> "value")).isEmpty();
    }

    @Test
    void mapArray_nonArrayNode_returnsEmptyList() throws Exception {
        JsonNode node = objectMapper.readTree("{\"key\":\"value\"}");
        assertThat(strategy.mapArray(node, n -> "x")).isEmpty();
    }

    @Test
    void mapArray_filtersNullMappings() throws Exception {
        JsonNode node = objectMapper.readTree("[1, 2, 3]");
        // Return null for even numbers, value for odd
        List<String> result = strategy.mapArray(node, n -> {
            int val = n.asInt();
            return val % 2 != 0 ? "odd-" + val : null;
        });
        assertThat(result).containsExactly("odd-1", "odd-3");
    }

    @Test
    void mapArray_allMapped_returnsAllElements() throws Exception {
        JsonNode node = objectMapper.readTree("[\"a\",\"b\",\"c\"]");
        List<String> result = strategy.mapArray(node, JsonNode::asText);
        assertThat(result).containsExactly("a", "b", "c");
    }

    // ---- safeExecute ----

    @Test
    void safeExecute_successfulAction_returnsResult() {
        Instant start = Instant.now();
        FetchResult success = FetchResult.empty(strategy.elapsed(start));
        FetchResult result = strategy.safeExecute(() -> success, start);
        assertThat(result).isSameAs(success);
    }

    @Test
    void safeExecute_throwingAction_returnsFetchResultError() {
        Instant start = Instant.now();
        FetchResult result = strategy.safeExecute(() -> {
            throw new RuntimeException("boom");
        }, start);
        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.errorMessage()).isEqualTo("boom");
    }

    @Test
    void safeExecute_nullMessageException_errorMessageIsNull() {
        Instant start = Instant.now();
        FetchResult result = strategy.safeExecute(() -> {
            throw new RuntimeException((String) null);
        }, start);
        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
    }

    // ---- supportedTypes / name / fetch (abstract contract) ----

    @Test
    void supportedTypes_returnsConfiguredTypes() {
        assertThat(strategy.supportedTypes()).containsExactly(AtsType.GREENHOUSE);
    }

    @Test
    void name_returnsConfiguredName() {
        assertThat(strategy.name()).isEqualTo("test-strategy");
    }

    // ─── Test double ───────────────────────────────────────────────────────────

    /**
     * Minimal concrete subclass exposing protected methods for testing.
     */
    static class TestStrategy extends AbstractAtsStrategy {

        @Override
        public Set<AtsType> supportedTypes() {
            return Set.of(AtsType.GREENHOUSE);
        }

        @Override
        public FetchResult fetch(FetchContext context) {
            return FetchResult.empty(elapsed(Instant.now()));
        }

        @Override
        public String name() {
            return "test-strategy";
        }

        // Widen visibility for test access
        @Override
        public java.time.Duration elapsed(Instant start) {
            return super.elapsed(start);
        }

        @Override
        public String truncate(String value, int maxLength) {
            return super.truncate(value, maxLength);
        }

        @Override
        public String stripHtml(String html) {
            return super.stripHtml(html);
        }

        @Override
        public LocalDate parseIsoDate(String dateStr) {
            return super.parseIsoDate(dateStr);
        }

        @Override
        public String textOrNull(JsonNode node, String path) {
            return super.textOrNull(node, path);
        }

        @Override
        public <T> List<T> mapArray(JsonNode arrayNode, java.util.function.Function<JsonNode, T> mapper) {
            return super.mapArray(arrayNode, mapper);
        }

        @Override
        public FetchResult safeExecute(java.util.function.Supplier<FetchResult> action, Instant start) {
            return super.safeExecute(action, start);
        }
    }
}
