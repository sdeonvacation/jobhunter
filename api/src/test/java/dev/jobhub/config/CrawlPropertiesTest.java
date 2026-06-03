package dev.jobhub.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CrawlPropertiesTest {

    @Test
    void validValues_preserved() {
        var props = new CrawlProperties(6, 3, 100, 60);
        assertThat(props.defaultFrequencyHours()).isEqualTo(6);
        assertThat(props.highPriorityFrequencyHours()).isEqualTo(3);
        assertThat(props.batchSize()).isEqualTo(100);
        assertThat(props.timeoutSeconds()).isEqualTo(60);
    }

    @Test
    void zeroOrNegativeValues_defaulted() {
        var props = new CrawlProperties(0, -1, 0, -5);
        assertThat(props.defaultFrequencyHours()).isEqualTo(4);
        assertThat(props.highPriorityFrequencyHours()).isEqualTo(2);
        assertThat(props.batchSize()).isEqualTo(50);
        assertThat(props.timeoutSeconds()).isEqualTo(30);
    }
}
