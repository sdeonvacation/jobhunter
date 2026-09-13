package dev.jobhunter.service;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.model.enums.JobSource;
import dev.jobhunter.repository.JobPostingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobTitleTranslatorTest {

    private static final JobSource SOURCE = JobSource.WISSENSCHAFTSSTELLEN;

    @Mock private AiProvider aiProvider;
    @Mock private JobPostingRepository jobPostingRepository;

    private JobTitleTranslator translator;

    @BeforeEach
    void setUp() {
        translator = new JobTitleTranslator(aiProvider, jobPostingRepository);
        lenient().when(aiProvider.isAvailable()).thenReturn(true);
    }

    private JobTitleTranslator.BatchResponse response(JobTitleTranslator.BatchResponse.Item... items) {
        return new JobTitleTranslator.BatchResponse(List.of(items));
    }

    private JobTitleTranslator.BatchResponse.Item item(int index, String english) {
        return new JobTitleTranslator.BatchResponse.Item(index, english);
    }

    private List<String> numberedTitles(int count) {
        List<String> titles = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            titles.add("Titel" + i);
        }
        return titles;
    }

    /** Echoes the numbered list it receives back as a translation ("<n>. X" -> english "EN: X"). */
    private JobTitleTranslator.BatchResponse echoResponse(String numberedList) {
        List<JobTitleTranslator.BatchResponse.Item> items = new ArrayList<>();
        for (String line : numberedList.split("\n")) {
            int dot = line.indexOf(". ");
            if (dot < 0) {
                continue;
            }
            int index = Integer.parseInt(line.substring(0, dot).trim());
            items.add(item(index, "EN: " + line.substring(dot + 2)));
        }
        return new JobTitleTranslator.BatchResponse(items);
    }

    @Test
    @DisplayName("translates all misses in exactly one batched AI call")
    void oneBatchedCallForAllMisses() {
        when(aiProvider.extract(anyString(), anyString(), eq(JobTitleTranslator.BatchResponse.class)))
                .thenReturn(response(
                        item(1, "Software Engineer"),
                        item(2, "Data Scientist")));

        Map<String, String> result = translator.translate(SOURCE,
                List.of("Softwareentwickler", "Datenwissenschaftler"));

        assertThat(result)
                .containsEntry("Softwareentwickler", "Software Engineer")
                .containsEntry("Datenwissenschaftler", "Data Scientist");
        verify(aiProvider, times(1))
                .extract(anyString(), anyString(), eq(JobTitleTranslator.BatchResponse.class));
    }

    @Test
    @DisplayName("25 misses are split into chunks of 10 -> exactly 3 AI calls, all translated")
    void chunksMissesIntoBatchesOfTen() {
        when(aiProvider.extract(anyString(), anyString(), eq(JobTitleTranslator.BatchResponse.class)))
                .thenAnswer(invocation -> echoResponse(invocation.getArgument(1)));

        Map<String, String> result = translator.translate(SOURCE, numberedTitles(25));

        assertThat(result).hasSize(25);
        for (int i = 1; i <= 25; i++) {
            assertThat(result).containsEntry("Titel" + i, "EN: Titel" + i);
        }
        verify(aiProvider, times(3))
                .extract(anyString(), anyString(), eq(JobTitleTranslator.BatchResponse.class));
    }

    @Test
    @DisplayName("a failing chunk falls back to originals while the other chunks still translate")
    void chunkFailureIsIsolated() {
        AtomicInteger calls = new AtomicInteger();
        when(aiProvider.extract(anyString(), anyString(), eq(JobTitleTranslator.BatchResponse.class)))
                .thenAnswer(invocation -> {
                    if (calls.incrementAndGet() == 2) {
                        throw new RuntimeException("client timeout");
                    }
                    return echoResponse(invocation.getArgument(1));
                });

        Map<String, String> result = translator.translate(SOURCE, numberedTitles(25));

        assertThat(result).hasSize(25);
        assertThat(result)
                .containsEntry("Titel1", "EN: Titel1")
                .containsEntry("Titel10", "EN: Titel10")
                .containsEntry("Titel11", "Titel11")
                .containsEntry("Titel20", "Titel20")
                .containsEntry("Titel21", "EN: Titel21")
                .containsEntry("Titel25", "EN: Titel25");
        verify(aiProvider, times(3))
                .extract(anyString(), anyString(), eq(JobTitleTranslator.BatchResponse.class));
    }

    @Test
    @DisplayName("translateExisting updates only rows whose title changes and preserves rawContent")
    void translateExistingUpdatesChangedRowsOnly() {
        Map<String, Object> existingRaw = new HashMap<>();
        existingRaw.put("salary", "x");
        JobPosting german = JobPosting.builder()
                .title("Softwareentwickler").rawContent(existingRaw).build();
        JobPosting alreadyEnglish = JobPosting.builder()
                .title("Software Engineer").build();
        when(jobPostingRepository.findUntranslatedBySource("WISSENSCHAFTSSTELLEN"))
                .thenReturn(List.of(german, alreadyEnglish));
        when(aiProvider.extract(anyString(), anyString(), eq(JobTitleTranslator.BatchResponse.class)))
                .thenReturn(response(item(1, "Software Engineer"), item(2, "Software Engineer")));

        int updated = translator.translateExisting(SOURCE);

        assertThat(updated).isEqualTo(1);
        assertThat(german.getTitle()).isEqualTo("Software Engineer");
        assertThat(german.getRawContent())
                .containsEntry("titleOriginal", "Softwareentwickler")
                .containsEntry("salary", "x");
        assertThat(alreadyEnglish.getTitle()).isEqualTo("Software Engineer");
        assertThat(alreadyEnglish.getRawContent()).isNull();
        verify(jobPostingRepository, times(1)).save(german);
        verify(jobPostingRepository, never()).save(alreadyEnglish);

        // Second run: cached translations, unchanged titles -> no writes (idempotent).
        clearInvocations(aiProvider, jobPostingRepository);

        int secondRun = translator.translateExisting(SOURCE);

        assertThat(secondRun).isZero();
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
        verify(jobPostingRepository, never()).save(any());
    }

    @Test
    @DisplayName("a second call for the same titles makes zero AI calls (cache hit)")
    void secondCallUsesCache() {
        when(aiProvider.extract(anyString(), anyString(), eq(JobTitleTranslator.BatchResponse.class)))
                .thenReturn(response(item(1, "Software Engineer")));

        translator.translate(SOURCE, List.of("Softwareentwickler"));
        clearInvocations(aiProvider);

        Map<String, String> second = translator.translate(SOURCE, List.of("Softwareentwickler"));

        assertThat(second).containsEntry("Softwareentwickler", "Software Engineer");
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
        // The cache is seeded from the repository at most once per source per JVM.
        verify(jobPostingRepository, times(1)).findTranslatedTitlesBySource("WISSENSCHAFTSSTELLEN");
    }

    @Test
    @DisplayName("partial AI response: the missing index falls back to the original title")
    void partialResponseFallsBackToOriginal() {
        when(aiProvider.extract(anyString(), anyString(), eq(JobTitleTranslator.BatchResponse.class)))
                .thenReturn(response(item(1, "Software Engineer")));

        Map<String, String> result = translator.translate(SOURCE,
                List.of("Softwareentwickler", "Datenwissenschaftler"));

        assertThat(result)
                .containsEntry("Softwareentwickler", "Software Engineer")
                .containsEntry("Datenwissenschaftler", "Datenwissenschaftler");
    }

    @Test
    @DisplayName("unavailable provider: originals returned, no AI call")
    void unavailableProviderReturnsOriginals() {
        when(aiProvider.isAvailable()).thenReturn(false);

        Map<String, String> result = translator.translate(SOURCE, List.of("Softwareentwickler"));

        assertThat(result).containsEntry("Softwareentwickler", "Softwareentwickler");
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("AI failure: originals returned, no exception propagated")
    void extractThrowsReturnsOriginals() {
        when(aiProvider.extract(anyString(), anyString(), eq(JobTitleTranslator.BatchResponse.class)))
                .thenThrow(new RuntimeException("AI down"));

        Map<String, String> result = translator.translate(SOURCE,
                List.of("Softwareentwickler", "Datenwissenschaftler"));

        assertThat(result)
                .containsEntry("Softwareentwickler", "Softwareentwickler")
                .containsEntry("Datenwissenschaftler", "Datenwissenschaftler");
    }

    @Test
    @DisplayName("null, empty and blank-only input returns an empty map")
    void blankInputReturnsEmptyMap() {
        assertThat(translator.translate(SOURCE, null)).isEmpty();
        assertThat(translator.translate(SOURCE, List.of())).isEmpty();
        assertThat(translator.translate(SOURCE, List.of("", "   "))).isEmpty();
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("duplicate titles are sent to the AI only once")
    void duplicateTitlesSentOnce() {
        when(aiProvider.extract(anyString(), anyString(), eq(JobTitleTranslator.BatchResponse.class)))
                .thenReturn(response(
                        item(1, "Software Engineer"),
                        item(2, "Data Scientist")));
        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);

        translator.translate(SOURCE,
                List.of("Softwareentwickler", "Softwareentwickler", "Datenwissenschaftler"));

        verify(aiProvider, times(1))
                .extract(anyString(), contentCaptor.capture(), eq(JobTitleTranslator.BatchResponse.class));
        assertThat(contentCaptor.getValue())
                .containsOnlyOnce("Softwareentwickler")
                .contains("Datenwissenschaftler");
    }

    @Test
    @DisplayName("cache is seeded once from previously persisted translations")
    void cacheSeededFromRepository() {
        when(jobPostingRepository.findTranslatedTitlesBySource("WISSENSCHAFTSSTELLEN"))
                .thenReturn(List.<Object[]>of(new Object[]{"Software Engineer", "Softwareentwickler"}));

        Map<String, String> result = translator.translate(SOURCE, List.of("Softwareentwickler"));

        assertThat(result).containsEntry("Softwareentwickler", "Software Engineer");
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
    }
}
