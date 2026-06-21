package dev.jobhunter.filter.visa;

import dev.jobhunter.model.enums.VisaSponsorship;
import dev.jobhunter.service.PersonalProfile;
import dev.jobhunter.service.PersonalProfileLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VisaDetectionChainTest {

    private PersonalProfileLoader createLoader(boolean aiEnabled, List<String> positive, List<String> negative) {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        var aiFallback = new PersonalProfile.AiFallbackConfig(aiEnabled, 4000, 50);
        var visaConfig = new PersonalProfile.VisaSponsorshipFilterConfig(
                List.of(), List.of(), List.of(),
                positive, negative,
                "skip", aiFallback
        );
        var filterConfig = new PersonalProfile.FilterConfig(null, null, null, null, visaConfig);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                filterConfig, null, null, null));
        return loader;
    }

    @Test
    void regexDefinitive_returnsWithoutAi() {
        var loader = createLoader(true, List.of("visa sponsorship"), List.of());
        var regexStrategy = new RegexVisaDetectionStrategy(loader);
        var aiStrategy = mock(AiVisaDetectionStrategy.class);

        var chain = new VisaDetectionChain(regexStrategy, aiStrategy, loader);
        var result = chain.evaluate("We offer visa sponsorship.");

        assertThat(result.status()).isEqualTo(VisaSponsorship.CONFIRMED);
        verifyNoInteractions(aiStrategy);
    }

    @Test
    void regexRejected_returnsWithoutAi() {
        var loader = createLoader(true, List.of(), List.of("no sponsorship"));
        var regexStrategy = new RegexVisaDetectionStrategy(loader);
        var aiStrategy = mock(AiVisaDetectionStrategy.class);

        var chain = new VisaDetectionChain(regexStrategy, aiStrategy, loader);
        var result = chain.evaluate("We provide no sponsorship.");

        assertThat(result.status()).isEqualTo(VisaSponsorship.REJECTED);
        verifyNoInteractions(aiStrategy);
    }

    @Test
    void regexUnclear_aiEnabled_fallsBackToAi() {
        var loader = createLoader(true, List.of("visa sponsorship"), List.of());
        var regexStrategy = new RegexVisaDetectionStrategy(loader);
        var aiStrategy = mock(AiVisaDetectionStrategy.class);
        when(aiStrategy.detect(anyString())).thenReturn(VisaDetectionResult.confirmed(0.7, "AI detected"));

        var chain = new VisaDetectionChain(regexStrategy, aiStrategy, loader);
        var result = chain.evaluate("Looking for a backend engineer in Berlin.");

        assertThat(result.status()).isEqualTo(VisaSponsorship.CONFIRMED);
        assertThat(result.confidence()).isEqualTo(0.7);
        verify(aiStrategy).detect(anyString());
    }

    @Test
    void regexUnclear_aiDisabled_returnsUnknown() {
        var loader = createLoader(false, List.of("visa sponsorship"), List.of());
        var regexStrategy = new RegexVisaDetectionStrategy(loader);
        var aiStrategy = mock(AiVisaDetectionStrategy.class);

        var chain = new VisaDetectionChain(regexStrategy, aiStrategy, loader);
        var result = chain.evaluate("Looking for a backend engineer in Berlin.");

        assertThat(result.status()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(result.reason()).isEqualTo("no signal detected");
        verifyNoInteractions(aiStrategy);
    }

    @Test
    void nullConfig_regexUnclear_returnsUnknown() {
        PersonalProfileLoader loader = mock(PersonalProfileLoader.class);
        when(loader.getProfile()).thenReturn(new PersonalProfile(
                "", "", 0, List.of(),
                new PersonalProfile.Preferences(List.of(), "FULL_TIME", 0, List.of(), List.of(), List.of()),
                null, null, null, null));
        var regexStrategy = new RegexVisaDetectionStrategy(loader);
        var aiStrategy = mock(AiVisaDetectionStrategy.class);

        var chain = new VisaDetectionChain(regexStrategy, aiStrategy, loader);
        var result = chain.evaluate("Some description");

        assertThat(result.status()).isEqualTo(VisaSponsorship.UNKNOWN);
        assertThat(result.reason()).isEqualTo("no signal detected");
    }
}
