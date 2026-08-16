package dev.jobhunter.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuartzConfigTest {

    @Test
    void startupJitterSeconds_returnsConfiguredBounds() {
        assertThat(QuartzConfig.startupJitterSeconds(30, ignored -> 0)).isZero();
        assertThat(QuartzConfig.startupJitterSeconds(30, ignored -> 30)).isEqualTo(30);
    }

    @Test
    void startupJitterSeconds_computesRandomValueOnce() {
        AtomicInteger calls = new AtomicInteger();

        int jitter = QuartzConfig.startupJitterSeconds(10, ignored -> {
            calls.incrementAndGet();
            return 7;
        });

        assertThat(jitter).isEqualTo(7);
        assertThat(calls).hasValue(1);
    }

    @Test
    void startupJitterSeconds_rejectsInvalidConfigurationAndRandomValues() {
        assertThatThrownBy(() -> QuartzConfig.startupJitterSeconds(-1, ignored -> 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuartzConfig.startupJitterSeconds(31, ignored -> 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuartzConfig.startupJitterSeconds(10, ignored -> 11))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
