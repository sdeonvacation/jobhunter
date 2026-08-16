package dev.jobhunter.strategy.aggregator;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobSpyCommandTest {

    private final CliStrategy strategy = new CliStrategy("npx");

    @Test
    void remoteUsesRemoteFlagInsteadOfCountry() {
        List<String> command = strategy.buildCommand("java", "remote", 25, new File("jobs.json"));

        assertThat(command).contains("-l", "remote", "-r");
        assertThat(command).doesNotContain("-c");
    }

    @Test
    void countryUsesCountryArgument() {
        for (String location : List.of("Germany", "Netherlands")) {
            List<String> command = strategy.buildCommand("java", location, 25, new File("jobs.json"));

            assertThat(command).containsSubsequence("-l", location, "-c", location.toLowerCase());
            assertThat(command).doesNotContain("-r");
        }
    }
}
