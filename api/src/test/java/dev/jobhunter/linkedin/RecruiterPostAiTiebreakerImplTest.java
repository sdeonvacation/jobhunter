package dev.jobhunter.linkedin;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.model.enums.AtsType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecruiterPostAiTiebreakerImplTest {

    @Mock
    private AiProvider aiProvider;

    private RecruiterPostAiTiebreakerImpl tiebreaker;

    private final SignalScorer.CandidatePost post = new SignalScorer.CandidatePost(
            "https://www.linkedin.com/posts/1", "Jane", "Recruiter at Acme",
            "https://linkedin.com/in/jane", "We are hiring Java engineers", "2 days ago");

    private final JobContextResolver.JobContext ctx = new JobContextResolver.JobContext(
            "Java Engineer", "Acme", "Berlin", null, "https://jobs.lever.co/acme/123", AtsType.LEVER, null, null);

    @BeforeEach
    void setUp() {
        tiebreaker = new RecruiterPostAiTiebreakerImpl(aiProvider, true);
    }

    @Test
    @DisplayName("Disabled AI verification returns UNKNOWN without touching the provider")
    void disabledReturnsUnknown() {
        RecruiterPostAiTiebreakerImpl disabled = new RecruiterPostAiTiebreakerImpl(aiProvider, false);

        assertThat(disabled.classify(post, ctx)).isEqualTo(RecruiterPostAiTiebreaker.AiVerdict.UNKNOWN);
        verifyNoInteractions(aiProvider);
    }

    @Test
    @DisplayName("Unavailable provider returns UNKNOWN")
    void unavailableProviderReturnsUnknown() {
        when(aiProvider.isAvailable()).thenReturn(false);

        assertThat(tiebreaker.classify(post, ctx)).isEqualTo(RecruiterPostAiTiebreaker.AiVerdict.UNKNOWN);
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Extraction with refersToThisOpening=true yields YES")
    void refersToThisOpeningYieldsYes() {
        when(aiProvider.isAvailable()).thenReturn(true);
        when(aiProvider.extract(anyString(), anyString(), eq(RecruiterPostAiTiebreakerImpl.AiClassification.class)))
                .thenReturn(new RecruiterPostAiTiebreakerImpl.AiClassification(true, "matches the opening"));

        assertThat(tiebreaker.classify(post, ctx)).isEqualTo(RecruiterPostAiTiebreaker.AiVerdict.YES);
    }

    @Test
    @DisplayName("Extraction with refersToThisOpening=false yields NO")
    void notThisOpeningYieldsNo() {
        when(aiProvider.isAvailable()).thenReturn(true);
        when(aiProvider.extract(anyString(), anyString(), eq(RecruiterPostAiTiebreakerImpl.AiClassification.class)))
                .thenReturn(new RecruiterPostAiTiebreakerImpl.AiClassification(false, "different role"));

        assertThat(tiebreaker.classify(post, ctx)).isEqualTo(RecruiterPostAiTiebreaker.AiVerdict.NO);
    }

    @Test
    @DisplayName("Provider exception yields UNKNOWN")
    void providerExceptionYieldsUnknown() {
        when(aiProvider.isAvailable()).thenReturn(true);
        when(aiProvider.extract(anyString(), anyString(), eq(RecruiterPostAiTiebreakerImpl.AiClassification.class)))
                .thenThrow(new RuntimeException("provider down"));

        assertThat(tiebreaker.classify(post, ctx)).isEqualTo(RecruiterPostAiTiebreaker.AiVerdict.UNKNOWN);
    }

    @Test
    @DisplayName("Null classification result yields UNKNOWN")
    void nullClassificationYieldsUnknown() {
        when(aiProvider.isAvailable()).thenReturn(true);
        when(aiProvider.extract(anyString(), anyString(), eq(RecruiterPostAiTiebreakerImpl.AiClassification.class)))
                .thenReturn(null);

        assertThat(tiebreaker.classify(post, ctx)).isEqualTo(RecruiterPostAiTiebreaker.AiVerdict.UNKNOWN);
    }

    @Test
    @DisplayName("Null post or context yields UNKNOWN")
    void nullPostOrContextYieldsUnknown() {
        when(aiProvider.isAvailable()).thenReturn(true);

        assertThat(tiebreaker.classify(null, ctx)).isEqualTo(RecruiterPostAiTiebreaker.AiVerdict.UNKNOWN);
        assertThat(tiebreaker.classify(post, null)).isEqualTo(RecruiterPostAiTiebreaker.AiVerdict.UNKNOWN);
        verify(aiProvider, never()).extract(anyString(), anyString(), any());
    }
}