package dev.jobhub.extraction;

import dev.jobhub.model.enums.ExtractionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionResultTest {

    @Test
    void success_setsCorrectFields() {
        var jobs = List.of(
                new RawJobData("1", "Dev", "Berlin", "desc", "url", "{}", null, null, null, LocalDate.now())
        );
        var result = ExtractionResult.success(jobs, Duration.ofMillis(500));

        assertThat(result.status()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.totalFound()).isEqualTo(1);
        assertThat(result.errorMessage()).isNull();
        assertThat(result.elapsed()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void empty_returnsEmptyList() {
        var result = ExtractionResult.empty(Duration.ofMillis(100));

        assertThat(result.status()).isEqualTo(ExtractionStatus.EMPTY);
        assertThat(result.jobs()).isEmpty();
        assertThat(result.totalFound()).isZero();
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    void error_preservesMessage() {
        var result = ExtractionResult.error("connection timeout", Duration.ofSeconds(30));

        assertThat(result.status()).isEqualTo(ExtractionStatus.ERROR);
        assertThat(result.jobs()).isEmpty();
        assertThat(result.totalFound()).isZero();
        assertThat(result.errorMessage()).isEqualTo("connection timeout");
    }

    @Test
    void rawJobData_preservesSalaryFields() {
        var job = new RawJobData(
                "ext-1", "Engineer", "Munich", "build things", "https://apply.com",
                "{}", new BigDecimal("80000"), new BigDecimal("120000"), "EUR", LocalDate.of(2024, 6, 1)
        );

        assertThat(job.salaryMin()).isEqualByComparingTo("80000");
        assertThat(job.salaryMax()).isEqualByComparingTo("120000");
        assertThat(job.salaryCurrency()).isEqualTo("EUR");
    }
}
