package dev.jobhunter.filter.visa;

import dev.jobhunter.ai.AiProvider;
import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AiVisaDetectionStrategyTest {

    private AiVisaDetectionStrategy createStrategy(boolean enabled, int dailyLimit, int maxChars) {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        var aiFallback = new PersonalProfile.AiFallbackConfig(enabled, maxChars, dailyLimit);
        var visaConfig = new PersonalProfile.VisaSponsorshipFilterConfig(
                List.of(), List.of(), List.of(), List.of(), List.of(), "skip", aiFallback
        );
        var filterConfig = new PersonalProfile.FilterConfig(null, null, null, null, visaConfig);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                filterConfig, null, null, null));

        AiProvider aiProvider = mock(AiProvider.class);
        when(aiProvider.isAvailable()).thenReturn(true);
        when(aiProvider.generate(anyString(), anyString())).thenReturn("yes");

        return new AiVisaDetectionStrategy(aiProvider, loader);
    }

    private AiVisaDetectionStrategy createStrategyWithProvider(AiProvider aiProvider, boolean enabled) {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        var aiFallback = new PersonalProfile.AiFallbackConfig(enabled, 4000, 50);
        var visaConfig = new PersonalProfile.VisaSponsorshipFilterConfig(
                List.of(), List.of(), List.of(), List.of(), List.of(), "skip", aiFallback
        );
        var filterConfig = new PersonalProfile.FilterConfig(null, null, null, null, visaConfig);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                filterConfig, null, null, null));
        return new AiVisaDetectionStrategy(aiProvider, loader);
    }

    @Test
    void disabled_returnsUnknown() {
        var strategy = createStrategy(false, 50, 4000);
        var result = strategy.detect("Some description");
        assertThat(result.status()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(result.reason()).isEqualTo("AI disabled");
    }

    @Test
    void aiUnavailable_returnsUnknown() {
        AiProvider aiProvider = mock(AiProvider.class);
        when(aiProvider.isAvailable()).thenReturn(false);
        var strategy = createStrategyWithProvider(aiProvider, true);
        var result = strategy.detect("Some description");
        assertThat(result.status()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(result.reason()).isEqualTo("AI disabled");
    }

    @Test
    void aiReturnsYes_confirmedWithLowerConfidence() {
        AiProvider aiProvider = mock(AiProvider.class);
        when(aiProvider.isAvailable()).thenReturn(true);
        when(aiProvider.generate(anyString(), anyString())).thenReturn("yes");
        var strategy = createStrategyWithProvider(aiProvider, true);

        var result = strategy.detect("Job at a startup in Berlin");
        assertThat(result.status()).isEqualTo(VisaSponsorship.CONFIRMED);
        assertThat(result.confidence()).isEqualTo(0.7);
    }

    @Test
    void aiReturnsNo_rejectedWithLowerConfidence() {
        AiProvider aiProvider = mock(AiProvider.class);
        when(aiProvider.isAvailable()).thenReturn(true);
        when(aiProvider.generate(anyString(), anyString())).thenReturn("no");
        var strategy = createStrategyWithProvider(aiProvider, true);

        var result = strategy.detect("Must be authorized to work in Germany");
        assertThat(result.status()).isEqualTo(VisaSponsorship.REJECTED);
        assertThat(result.confidence()).isEqualTo(0.7);
    }

    @Test
    void aiReturnsUnclear_unknownResult() {
        AiProvider aiProvider = mock(AiProvider.class);
        when(aiProvider.isAvailable()).thenReturn(true);
        when(aiProvider.generate(anyString(), anyString())).thenReturn("unclear");
        var strategy = createStrategyWithProvider(aiProvider, true);

        var result = strategy.detect("Some ambiguous text");
        assertThat(result.status()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(result.reason()).contains("inconclusive");
    }

    @Test
    void aiThrowsException_returnsUnknownWithError() {
        AiProvider aiProvider = mock(AiProvider.class);
        when(aiProvider.isAvailable()).thenReturn(true);
        when(aiProvider.generate(anyString(), anyString())).thenThrow(new RuntimeException("API timeout"));
        var strategy = createStrategyWithProvider(aiProvider, true);

        var result = strategy.detect("Some description");
        assertThat(result.status()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(result.reason()).contains("AI error");
        assertThat(result.reason()).contains("API timeout");
    }

    @Test
    void dailyLimitExceeded_returnsUnknown() {
        var strategy = createStrategy(true, 1, 4000);

        // First call succeeds
        var first = strategy.detect("Description one");
        assertThat(first.status()).isEqualTo(VisaSponsorship.CONFIRMED);

        // Second call hits limit
        var second = strategy.detect("Description two");
        assertThat(second.status()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(second.reason()).isEqualTo("daily limit reached");
    }

    @Test
    void truncatesLongDescription() {
        AiProvider aiProvider = mock(AiProvider.class);
        when(aiProvider.isAvailable()).thenReturn(true);
        when(aiProvider.generate(anyString(), anyString())).thenReturn("yes");

        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        var aiFallback = new PersonalProfile.AiFallbackConfig(true, 10, 50);
        var visaConfig = new PersonalProfile.VisaSponsorshipFilterConfig(
                List.of(), List.of(), List.of(), List.of(), List.of(), "skip", aiFallback
        );
        var filterConfig = new PersonalProfile.FilterConfig(null, null, null, null, visaConfig);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                filterConfig, null, null, null));

        var strategy = new AiVisaDetectionStrategy(aiProvider, loader);
        strategy.detect("This is a very long description exceeding limit");

        verify(aiProvider).generate(anyString(), eq("This is a "));
    }

    @Test
    void nullAiFallbackConfig_disablesStrategy() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        var visaConfig = new PersonalProfile.VisaSponsorshipFilterConfig(
                List.of(), List.of(), List.of(), List.of(), List.of(), "skip", null
        );
        var filterConfig = new PersonalProfile.FilterConfig(null, null, null, null, visaConfig);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                filterConfig, null, null, null));

        AiProvider aiProvider = mock(AiProvider.class);
        var strategy = new AiVisaDetectionStrategy(aiProvider, loader);

        var result = strategy.detect("Some description");
        assertThat(result.status()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(result.reason()).isEqualTo("AI disabled");
        verifyNoInteractions(aiProvider);
    }
}
