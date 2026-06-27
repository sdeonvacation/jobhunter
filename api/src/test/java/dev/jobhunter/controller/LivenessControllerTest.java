package dev.jobhunter.controller;

import dev.jobhunter.dto.LivenessResultDto;
import dev.jobhunter.model.enums.LivenessStatus;
import dev.jobhunter.service.LivenessCheckService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LivenessControllerTest {

    @Mock private LivenessCheckService livenessCheckService;

    private LivenessController controller;

    @BeforeEach
    void setUp() {
        controller = new LivenessController(livenessCheckService);
    }

    @Test
    void checkLiveness_delegatesToService() {
        UUID jobId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        LivenessResultDto expected = new LivenessResultDto(
                jobId, LivenessStatus.ACTIVE.name(), now,
                "https://example.com/apply", "Page contains apply button");
        when(livenessCheckService.checkLiveness(jobId)).thenReturn(expected);

        ResponseEntity<LivenessResultDto> response = controller.checkLiveness(jobId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
        verify(livenessCheckService).checkLiveness(jobId);
    }

    @Test
    void checkLiveness_expiredJob_returnsOkWithExpiredStatus() {
        UUID jobId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        LivenessResultDto expected = new LivenessResultDto(
                jobId, LivenessStatus.EXPIRED.name(), now,
                "https://example.com/closed", "HTTP 404");
        when(livenessCheckService.checkLiveness(jobId)).thenReturn(expected);

        ResponseEntity<LivenessResultDto> response = controller.checkLiveness(jobId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo("EXPIRED");
    }

    @Test
    void getLivenessStatus_delegatesToService() {
        UUID jobId = UUID.randomUUID();
        LocalDateTime checked = LocalDateTime.of(2025, 6, 20, 14, 0);
        LivenessResultDto expected = new LivenessResultDto(
                jobId, LivenessStatus.UNCERTAIN.name(), checked,
                "https://example.com/jobs/1", null);
        when(livenessCheckService.getStatus(jobId)).thenReturn(expected);

        ResponseEntity<LivenessResultDto> response = controller.getLivenessStatus(jobId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
        verify(livenessCheckService).getStatus(jobId);
    }

    @Test
    void getLivenessStatus_neverChecked_returnsNullFields() {
        UUID jobId = UUID.randomUUID();
        LivenessResultDto expected = new LivenessResultDto(
                jobId, null, null, "https://example.com/jobs/2", null);
        when(livenessCheckService.getStatus(jobId)).thenReturn(expected);

        ResponseEntity<LivenessResultDto> response = controller.getLivenessStatus(jobId);

        assertThat(response.getBody().status()).isNull();
        assertThat(response.getBody().checkedAt()).isNull();
    }
}
