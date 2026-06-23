package dev.jobhunter.strategy.ats;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jobhunter.strategy.FetchContext;
import dev.jobhunter.strategy.FetchResult;
import dev.jobhunter.strategy.FetchStrategy;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.function.Supplier;

@Slf4j
public abstract class AbstractAtsStrategy implements FetchStrategy {

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]*>");
    private static final Map<String, String> HTML_ENTITIES = Map.of(
        "&amp;", "&", "&lt;", "<", "&gt;", ">",
        "&nbsp;", " ", "&quot;", "\"", "&#39;", "'", "&apos;", "'"
    );

    @Override
    public abstract java.util.Set<dev.jobhunter.model.enums.AtsType> supportedTypes();

    @Override
    public abstract FetchResult fetch(FetchContext context);

    @Override
    public abstract String name();

    protected Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }

    protected String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    protected String stripHtml(String html) {
        if (html == null || html.isBlank()) return "";
        String text = HTML_TAG_PATTERN.matcher(html).replaceAll("");
        for (Map.Entry<String, String> e : HTML_ENTITIES.entrySet()) {
            text = text.replace(e.getKey(), e.getValue());
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    protected LocalDate parseIsoDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return ZonedDateTime.parse(dateStr).toLocalDate();
        } catch (Exception e) {
            try { return LocalDate.parse(dateStr); } catch (Exception e2) { return null; }
        }
    }

    protected String textOrNull(JsonNode node, String path) {
        if (node == null) return null;
        String text = node.path(path).asText(null);
        return (text == null || text.isBlank()) ? null : text;
    }

    protected <T> List<T> mapArray(JsonNode arrayNode, Function<JsonNode, T> mapper) {
        if (arrayNode == null || !arrayNode.isArray()) return List.of();
        List<T> result = new ArrayList<>();
        for (JsonNode item : arrayNode) {
            T mapped = mapper.apply(item);
            if (mapped != null) result.add(mapped);
        }
        return result;
    }

    protected FetchResult safeExecute(Supplier<FetchResult> action, Instant start) {
        try {
            return action.get();
        } catch (Exception e) {
            log.error("[{}]: execution failed: {}", name(), e.getMessage(), e);
            return FetchResult.error(e.getMessage(), elapsed(start));
        }
    }
}
