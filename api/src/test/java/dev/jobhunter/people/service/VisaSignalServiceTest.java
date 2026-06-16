package dev.jobhunter.people.service;

import dev.jobhunter.model.Company;
import dev.jobhunter.people.model.enums.VisaFriendliness;
import dev.jobhunter.repository.CompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VisaSignalServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    private VisaSignalService service;

    @BeforeEach
    void setUp() {
        service = new VisaSignalService(companyRepository);
    }

    @Test
    void getSignals_companyNotFound_throws() {
        UUID companyId = UUID.randomUUID();
        when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSignals(companyId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Company not found");
    }

    @Test
    void getSignals_allSignalsTrue_returnsHigh() {
        UUID companyId = UUID.randomUUID();
        Company company = Company.builder()
                .id(companyId)
                .hasSponsoredBefore(true)
                .englishSpeaking(true)
                .internationalWorkforce(true)
                .build();
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        var signals = service.getSignals(companyId);

        assertThat(signals.derived()).isEqualTo(VisaFriendliness.HIGH);
        assertThat(signals.companyId()).isEqualTo(companyId);
        assertThat(signals.hasSponsoredBefore()).isTrue();
        assertThat(signals.englishSpeaking()).isTrue();
        assertThat(signals.internationalWorkforce()).isTrue();
    }

    @Test
    void getSignals_allFalse_returnsLow() {
        UUID companyId = UUID.randomUUID();
        Company company = Company.builder()
                .id(companyId)
                .hasSponsoredBefore(false)
                .englishSpeaking(false)
                .internationalWorkforce(false)
                .build();
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        var signals = service.getSignals(companyId);

        assertThat(signals.derived()).isEqualTo(VisaFriendliness.LOW);
    }

    @Test
    void getSignals_someNull_returnsUnknown() {
        UUID companyId = UUID.randomUUID();
        Company company = Company.builder()
                .id(companyId)
                .hasSponsoredBefore(true)
                .englishSpeaking(null)
                .internationalWorkforce(true)
                .build();
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        var signals = service.getSignals(companyId);

        assertThat(signals.derived()).isEqualTo(VisaFriendliness.UNKNOWN);
    }

    @Test
    void updateSignal_hasSponsoredBefore_updatesAndSaves() {
        UUID companyId = UUID.randomUUID();
        Company company = Company.builder()
                .id(companyId)
                .hasSponsoredBefore(false)
                .englishSpeaking(true)
                .internationalWorkforce(true)
                .build();
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        service.updateSignal(companyId, "hasSponsoredBefore", true);

        assertThat(company.getHasSponsoredBefore()).isTrue();
        assertThat(company.getVisaFriendliness()).isEqualTo("HIGH");
        verify(companyRepository).save(company);
    }

    @Test
    void updateSignal_englishSpeaking_updatesAndSaves() {
        UUID companyId = UUID.randomUUID();
        Company company = Company.builder()
                .id(companyId)
                .hasSponsoredBefore(false)
                .englishSpeaking(false)
                .internationalWorkforce(false)
                .build();
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        service.updateSignal(companyId, "englishSpeaking", true);

        assertThat(company.getEnglishSpeaking()).isTrue();
        assertThat(company.getVisaFriendliness()).isEqualTo("MEDIUM");
        verify(companyRepository).save(company);
    }

    @Test
    void updateSignal_internationalWorkforce_updatesAndSaves() {
        UUID companyId = UUID.randomUUID();
        Company company = Company.builder()
                .id(companyId)
                .hasSponsoredBefore(true)
                .englishSpeaking(true)
                .internationalWorkforce(false)
                .build();
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        service.updateSignal(companyId, "internationalWorkforce", true);

        assertThat(company.getInternationalWorkforce()).isTrue();
        assertThat(company.getVisaFriendliness()).isEqualTo("HIGH");
        verify(companyRepository).save(company);
    }

    @Test
    void updateSignal_unknownSignal_throws() {
        UUID companyId = UUID.randomUUID();
        Company company = Company.builder().id(companyId).build();
        when(companyRepository.findById(companyId)).thenReturn(Optional.of(company));

        assertThatThrownBy(() -> service.updateSignal(companyId, "bogusField", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown visa signal");
    }

    @Test
    void updateSignal_companyNotFound_throws() {
        UUID companyId = UUID.randomUUID();
        when(companyRepository.findById(companyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSignal(companyId, "englishSpeaking", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Company not found");
    }

    @ParameterizedTest
    @CsvSource({
            "true,true,true,HIGH",
            "true,true,false,MEDIUM",
            "true,false,false,MEDIUM",
            "false,false,false,LOW",
    })
    void deriveVisaFriendliness_variousCombinations(boolean sponsored, boolean english,
                                                     boolean international, VisaFriendliness expected) {
        assertThat(VisaSignalService.deriveVisaFriendliness(sponsored, english, international))
                .isEqualTo(expected);
    }

    @Test
    void deriveVisaFriendliness_anyNull_returnsUnknown() {
        assertThat(VisaSignalService.deriveVisaFriendliness(null, true, true)).isEqualTo(VisaFriendliness.UNKNOWN);
        assertThat(VisaSignalService.deriveVisaFriendliness(true, null, true)).isEqualTo(VisaFriendliness.UNKNOWN);
        assertThat(VisaSignalService.deriveVisaFriendliness(true, true, null)).isEqualTo(VisaFriendliness.UNKNOWN);
    }
}
