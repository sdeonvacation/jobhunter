package dev.jobhunter.people.service;

import dev.jobhunter.model.Company;
import dev.jobhunter.model.JobPosting;
import dev.jobhunter.repository.CompanyRepository;
import dev.jobhunter.repository.JobPostingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HiringVelocityCalculatorTest {

    @Mock
    private JobPostingRepository jobPostingRepository;
    @Mock
    private CompanyRepository companyRepository;

    private HiringVelocityCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new HiringVelocityCalculator(jobPostingRepository, companyRepository);
    }

    @Test
    void calculate_noJobs_returnsZero() {
        UUID companyId = UUID.randomUUID();
        when(jobPostingRepository.findByCompanyIdAndIsActiveTrue(companyId)).thenReturn(List.of());

        int velocity = calculator.calculate(companyId);

        assertThat(velocity).isZero();
    }

    @Test
    void calculate_allJobsWithin30Days_countsAll() {
        UUID companyId = UUID.randomUUID();
        List<JobPosting> jobs = List.of(
                jobWithDate(LocalDate.now().minusDays(5)),
                jobWithDate(LocalDate.now().minusDays(10)),
                jobWithDate(LocalDate.now())
        );
        when(jobPostingRepository.findByCompanyIdAndIsActiveTrue(companyId)).thenReturn(jobs);

        int velocity = calculator.calculate(companyId);

        assertThat(velocity).isEqualTo(3);
    }

    @Test
    void calculate_mixOfRecentAndOld_countsOnlyRecent() {
        UUID companyId = UUID.randomUUID();
        List<JobPosting> jobs = List.of(
                jobWithDate(LocalDate.now().minusDays(5)),
                jobWithDate(LocalDate.now().minusDays(31)),
                jobWithDate(LocalDate.now().minusDays(60))
        );
        when(jobPostingRepository.findByCompanyIdAndIsActiveTrue(companyId)).thenReturn(jobs);

        int velocity = calculator.calculate(companyId);

        assertThat(velocity).isEqualTo(1);
    }

    @Test
    void calculate_nullDiscoveredDate_excluded() {
        UUID companyId = UUID.randomUUID();
        JobPosting withDate = jobWithDate(LocalDate.now().minusDays(2));
        JobPosting withNull = JobPosting.builder().discoveredDate(null).build();
        when(jobPostingRepository.findByCompanyIdAndIsActiveTrue(companyId)).thenReturn(List.of(withDate, withNull));

        int velocity = calculator.calculate(companyId);

        assertThat(velocity).isEqualTo(1);
    }

    @Test
    void calculate_exactly30DaysAgo_included() {
        UUID companyId = UUID.randomUUID();
        List<JobPosting> jobs = List.of(jobWithDate(LocalDate.now().minusDays(30)));
        when(jobPostingRepository.findByCompanyIdAndIsActiveTrue(companyId)).thenReturn(jobs);

        int velocity = calculator.calculate(companyId);

        assertThat(velocity).isEqualTo(1);
    }

    @Test
    void calculateAll_updatesCompaniesAndReturnsMap() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Company c1 = Company.builder().id(id1).hiringVelocity(null).build();
        Company c2 = Company.builder().id(id2).hiringVelocity(5).build();

        when(companyRepository.findByIsActiveTrue()).thenReturn(List.of(c1, c2));
        when(jobPostingRepository.findByCompanyIdAndIsActiveTrue(id1))
                .thenReturn(List.of(jobWithDate(LocalDate.now())));
        when(jobPostingRepository.findByCompanyIdAndIsActiveTrue(id2))
                .thenReturn(List.of());

        Map<UUID, Integer> result = calculator.calculateAll();

        assertThat(result).hasSize(2);
        assertThat(result.get(id1)).isEqualTo(1);
        assertThat(result.get(id2)).isZero();
        verify(companyRepository).save(c1);
        verify(companyRepository).save(c2); // 5 -> 0, changed
    }

    @Test
    void calculateAll_noChangeInVelocity_doesNotSave() {
        UUID id1 = UUID.randomUUID();
        Company c1 = Company.builder().id(id1).hiringVelocity(0).build();

        when(companyRepository.findByIsActiveTrue()).thenReturn(List.of(c1));
        when(jobPostingRepository.findByCompanyIdAndIsActiveTrue(id1)).thenReturn(List.of());

        calculator.calculateAll();

        verify(companyRepository, never()).save(any());
    }

    private JobPosting jobWithDate(LocalDate date) {
        return JobPosting.builder().discoveredDate(date).build();
    }
}
