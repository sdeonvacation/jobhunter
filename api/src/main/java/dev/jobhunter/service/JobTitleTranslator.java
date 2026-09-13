package dev.jobhunter.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Source-scoped English translation of job titles.
 *
 * <p>All misses for a single invocation are translated in batches of at most
 * {@value #MAX_TITLES_PER_CALL} titles (one {@link AiProvider#extract} call per chunk), because a
 * single large request exceeds the client timeout. Every original title is translated at most once
 * per JVM thanks to a lazily seeded cache. The method never throws: each chunk is isolated, so a
 * failing chunk falls back to the original titles for that chunk only while the remaining chunks
 * proceed.
 */
@Slf4j
@Service
public class JobTitleTranslator {

    /** Upper bound on titles per AI call; larger batches exceed the configured client timeout. */
    private static final int MAX_TITLES_PER_CALL = 10;

    static final String SYSTEM_PROMPT = """
            Translate job titles into concise professional English job titles.
            For every numbered German job title below, produce one English translation that
            preserves the original meaning, seniority and specialization. Do not add, omit or
            reinterpret information.
            Return exactly one entry per input index, keeping the same index number.
            Respond with JSON only, in this exact shape:
            {"translations":[{"index":1,"english":"..."}]}
            """;

    private final AiProvider aiProvider;
    private final JobPostingRepository jobPostingRepository;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Set<JobSource> seededSources = ConcurrentHashMap.newKeySet();

    public JobTitleTranslator(AiProvider aiProvider, JobPostingRepository jobPostingRepository) {
        this.aiProvider = aiProvider;
        this.jobPostingRepository = jobPostingRepository;
    }

    /**
     * Translate the given titles for a source. Returns a map containing an entry for every
     * non-blank requested title; entries that could not be translated (unavailable provider,
     * AI failure or a missing index in a partial response) map to the original title.
     */
    public Map<String, String> translate(JobSource source, Collection<String> titles) {
        if (titles == null || titles.isEmpty()) {
            return Map.of();
        }
        List<String> distinct = titles.stream()
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }

        seedCacheOnce(source);

        Map<String, String> result = new LinkedHashMap<>();
        List<String> misses = new ArrayList<>();
        for (String title : distinct) {
            String cached = cache.get(title);
            if (cached != null) {
                result.put(title, cached);
            } else {
                misses.add(title);
            }
        }

        if (misses.isEmpty()) {
            return result;
        }
        if (!aiProvider.isAvailable()) {
            log.warn("AI provider unavailable; keeping {} job title(s) untranslated", misses.size());
            misses.forEach(title -> result.put(title, title));
            return result;
        }

        for (int start = 0; start < misses.size(); start += MAX_TITLES_PER_CALL) {
            List<String> chunk = misses.subList(start, Math.min(start + MAX_TITLES_PER_CALL, misses.size()));
            try {
                BatchResponse response = aiProvider.extract(SYSTEM_PROMPT, buildNumberedList(chunk), BatchResponse.class);
                Map<Integer, String> byIndex = indexedTranslations(response);
                for (int i = 0; i < chunk.size(); i++) {
                    String original = chunk.get(i);
                    String english = byIndex.get(i + 1);
                    if (english != null && !english.isBlank()) {
                        cache.put(original, english);
                        result.put(original, english);
                    } else {
                        // Missing/blank entry: fall back to the original, never cached.
                        result.put(original, original);
                    }
                }
            } catch (Exception e) {
                log.warn("Job title translation failed for source [{}] on a chunk of {} title(s); "
                        + "using original titles: {}", source, chunk.size(), e.getMessage());
                chunk.forEach(title -> result.put(title, title));
            }
        }
        return result;
    }

    /**
     * Backfill stored titles that were never translated (no {@code rawContent.titleOriginal}).
     * Rows whose translation is unchanged are left untouched, so repeated runs are idempotent.
     *
     * @return the number of rows whose title was updated
     */
    public int translateExisting(JobSource source) {
        if (source == null) {
            return 0;
        }
        int updated = 0;
        try {
            List<JobPosting> rows = jobPostingRepository.findUntranslatedBySource(source.name());
            if (rows == null || rows.isEmpty()) {
                return 0;
            }
            List<String> titles = rows.stream()
                    .map(JobPosting::getTitle)
                    .filter(t -> t != null && !t.isBlank())
                    .distinct()
                    .toList();
            if (titles.isEmpty()) {
                return 0;
            }

            Map<String, String> translations = translate(source, titles);
            for (JobPosting row : rows) {
                String original = row.getTitle();
                if (original == null || original.isBlank()) {
                    continue;
                }
                String english = translations.get(original);
                if (english == null || english.isBlank() || english.equals(original)) {
                    continue;
                }
                row.setTitle(english);
                Map<String, Object> raw = row.getRawContent();
                Map<String, Object> updatedRaw = raw == null ? new LinkedHashMap<>() : new LinkedHashMap<>(raw);
                updatedRaw.put("titleOriginal", original);
                row.setRawContent(updatedRaw);
                jobPostingRepository.save(row);
                updated++;
            }
        } catch (Exception e) {
            log.warn("Title backfill for source [{}] failed after {} update(s): {}",
                    source, updated, e.getMessage());
        }
        return updated;
    }

    private Map<Integer, String> indexedTranslations(BatchResponse response) {
        Map<Integer, String> byIndex = new LinkedHashMap<>();
        if (response != null && response.translations() != null) {
            for (BatchResponse.Item item : response.translations()) {
                if (item != null && item.english() != null && !item.english().isBlank()) {
                    byIndex.put(item.index(), item.english().strip());
                }
            }
        }
        return byIndex;
    }


    private void seedCacheOnce(JobSource source) {
        if (source == null || !seededSources.add(source)) {
            return;
        }
        try {
            List<Object[]> rows = jobPostingRepository.findTranslatedTitlesBySource(source.name());
            if (rows == null) {
                return;
            }
            for (Object[] row : rows) {
                // Rows are [translatedTitle, originalTitle].
                if (row != null && row.length >= 2 && row[0] != null && row[1] != null) {
                    cache.put(row[1].toString(), row[0].toString());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to seed title-translation cache for source [{}]: {}", source, e.getMessage());
        }
    }

    private String buildNumberedList(List<String> titles) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < titles.size(); i++) {
            sb.append(i + 1).append(". ").append(titles.get(i)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BatchResponse(List<Item> translations) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Item(int index, String english) {
        }
    }
}
