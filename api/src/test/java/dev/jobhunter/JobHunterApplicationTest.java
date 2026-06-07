package dev.jobhunter;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class JobHunterApplicationTest {

    @Test
    void contextLoads() {
        // Verifies Spring context starts successfully with all configuration
    }
}
