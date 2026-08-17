package dev.jobhunter.ai;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class FallbackAiProviderTest {

    @Test
    void extractionGenerationUsesFallbackExtractionPath() {
        AiProvider primary = mock(AiProvider.class);
        AiProvider fallback = mock(AiProvider.class);
        when(primary.isAvailable()).thenReturn(false);

        new FallbackAiProvider(primary, fallback).generateExtraction("extract", "content");

        verify(fallback).generateExtraction("extract", "content");
        verify(fallback, never()).generate("extract", "content");
    }

    @Test
    void generationKeepsTailoringPath() {
        AiProvider primary = mock(AiProvider.class);
        AiProvider fallback = mock(AiProvider.class);
        when(primary.isAvailable()).thenReturn(false);

        new FallbackAiProvider(primary, fallback).generate("tailor", "resume");

        verify(fallback).generate("tailor", "resume");
        verify(fallback, never()).generateExtraction("tailor", "resume");
    }
}
