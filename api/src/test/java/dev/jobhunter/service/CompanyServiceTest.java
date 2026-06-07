package dev.jobhunter.service;

import dev.jobhunter.model.Company;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {

    @Mock private CompanyRepository companyRepository;
    private CompanyService companyService;

    @BeforeEach
    void setUp() {
        companyService = new CompanyService(companyRepository);
    }

    @ParameterizedTest
    @CsvSource({
            "SAP SE, sap",
            "Deutsche Bank AG, deutsche bank",
            "Stripe Inc, stripe",
            "Revolut Ltd, revolut",
            "Siemens GmbH, siemens",
            "Microsoft Corp, microsoft",
            "Shopify LLC, shopify",
            "ING BV, ing",
            "  Spaces Corp.  , spaces"
    })
    void normalizeCompanyName_stripsSuffixes(String input, String expected) {
        assertThat(companyService.normalizeCompanyName(input)).isEqualTo(expected);
    }

    @Test
    void normalizeCompanyName_null_returnsEmpty() {
        assertThat(companyService.normalizeCompanyName(null)).isEmpty();
    }

    @Test
    void normalizeCompanyName_blank_returnsEmpty() {
        assertThat(companyService.normalizeCompanyName("  ")).isEmpty();
    }

    @Test
    void normalizeCompanyName_noSuffix_lowercasesOnly() {
        assertThat(companyService.normalizeCompanyName("Netflix")).isEqualTo("netflix");
    }

    @Test
    void findById_delegatesToRepo() {
        var id = UUID.randomUUID();
        var company = Company.builder().id(id).name("Test").build();
        when(companyRepository.findById(id)).thenReturn(Optional.of(company));

        assertThat(companyService.findById(id)).contains(company);
    }

    @Test
    void findByNormalizedName_normalizesBeforeLookup() {
        when(companyRepository.findByNormalizedName("stripe"))
                .thenReturn(Optional.of(Company.builder().name("Stripe Inc").build()));

        var result = companyService.findByNormalizedName("Stripe Inc");
        assertThat(result).isPresent();
        verify(companyRepository).findByNormalizedName("stripe");
    }

    @Test
    void save_setsNormalizedNameIfMissing() {
        var company = Company.builder().name("TestCo GmbH").build();
        when(companyRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        companyService.save(company);

        assertThat(company.getNormalizedName()).isEqualTo("testco");
        verify(companyRepository).save(company);
    }

    @Test
    void save_preservesExistingNormalizedName() {
        var company = Company.builder().name("TestCo").normalizedName("custom-name").build();
        when(companyRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        companyService.save(company);

        assertThat(company.getNormalizedName()).isEqualTo("custom-name");
    }
}
