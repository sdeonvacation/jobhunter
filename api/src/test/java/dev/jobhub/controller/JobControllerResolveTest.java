package dev.jobhub.controller;

import dev.jobhub.repository.JobPostingRepository;
import dev.jobhub.repository.JobSkillRepository;
import dev.jobhub.service.DailyDigestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobControllerResolveTest {

    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private JobSkillRepository jobSkillRepository;
    @Mock private DailyDigestService dailyDigestService;

    private JobController controller;

    @BeforeEach
    void setUp() {
        controller = new JobController(jobPostingRepository, jobSkillRepository, dailyDigestService);
    }

    @Test
    void resolveId_prefixTooShort_returnsBadRequest() {
        var response = controller.resolveId("abc");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resolveId_prefixTooLong_returnsBadRequest() {
        var response = controller.resolveId("a".repeat(37));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resolveId_fullUuid_existsReturnsOk() {
        UUID id = UUID.randomUUID();
        when(jobPostingRepository.existsById(id)).thenReturn(true);

        var response = controller.resolveId(id.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("id", id.toString()));
    }

    @Test
    void resolveId_fullUuid_notExistsReturnsNotFound() {
        UUID id = UUID.randomUUID();
        when(jobPostingRepository.existsById(id)).thenReturn(false);

        var response = controller.resolveId(id.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void resolveId_fullUuid_invalidFormat_returnsBadRequest() {
        // 36 chars but not valid UUID format
        var response = controller.resolveId("not-a-valid-uuid-but-exactly-36chars");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resolveId_prefix_found_returnsOk() {
        UUID id = UUID.randomUUID();
        String prefix = id.toString().substring(0, 8);
        when(jobPostingRepository.findIdByPrefix(prefix)).thenReturn(Optional.of(id));

        var response = controller.resolveId(prefix);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("id", id.toString()));
    }

    @Test
    void resolveId_prefix_notFound_returnsNotFound() {
        when(jobPostingRepository.findIdByPrefix("a3f2c8d1")).thenReturn(Optional.empty());

        var response = controller.resolveId("a3f2c8d1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void resolveId_sixCharPrefix_accepted() {
        UUID id = UUID.randomUUID();
        String prefix = id.toString().substring(0, 6);
        when(jobPostingRepository.findIdByPrefix(prefix)).thenReturn(Optional.of(id));

        var response = controller.resolveId(prefix);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void resolveId_fiveCharPrefix_rejected() {
        var response = controller.resolveId("a3f2c");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
