package dev.jobhub;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class LiquibaseMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void allTablesCreated() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                String.class
        );

        assertAll(
                () -> assertTrue(tables.contains("company"), "company table missing"),
                () -> assertTrue(tables.contains("career_endpoint"), "career_endpoint table missing"),
                () -> assertTrue(tables.contains("job_posting"), "job_posting table missing"),
                () -> assertTrue(tables.contains("job_skill"), "job_skill table missing"),
                () -> assertTrue(tables.contains("match_score"), "match_score table missing"),
                () -> assertTrue(tables.contains("opportunity_score"), "opportunity_score table missing"),
                () -> assertTrue(tables.contains("application"), "application table missing"),
                () -> assertTrue(tables.contains("job_outcome"), "job_outcome table missing"),
                () -> assertTrue(tables.contains("discovery_event"), "discovery_event table missing"),
                () -> assertTrue(tables.contains("resolution_result"), "resolution_result table missing")
        );
    }

    @Test
    void seedCompaniesLoaded() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM company", Integer.class);
        assertNotNull(count);
        assertTrue(count >= 50, "Expected at least 50 seeded companies, got " + count);
    }

    @Test
    void seedEndpointsLoaded() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM career_endpoint", Integer.class);
        assertNotNull(count);
        assertTrue(count >= 50, "Expected at least 50 seeded endpoints, got " + count);
    }

    @Test
    void companyEndpointForeignKeyValid() {
        // All endpoints reference existing companies
        Integer orphans = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM career_endpoint ce LEFT JOIN company c ON ce.company_id = c.id WHERE c.id IS NULL",
                Integer.class
        );
        assertEquals(0, orphans, "Found orphaned career_endpoint rows");
    }

    @Test
    void indexesExist() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'",
                String.class
        );

        assertAll(
                () -> assertTrue(indexes.contains("idx_company_normalized_name"), "idx_company_normalized_name missing"),
                () -> assertTrue(indexes.contains("idx_job_source_external"), "idx_job_source_external missing"),
                () -> assertTrue(indexes.contains("idx_endpoint_next_crawl"), "idx_endpoint_next_crawl missing"),
                () -> assertTrue(indexes.contains("idx_opp_score"), "idx_opp_score missing")
        );
    }

    @Test
    void knownCompaniesSeededCorrectly() {
        Map<String, Object> personio = jdbcTemplate.queryForMap(
                "SELECT c.name, ce.ats_type, ce.url FROM company c JOIN career_endpoint ce ON ce.company_id = c.id WHERE c.normalized_name = 'personio'"
        );
        assertEquals("Personio", personio.get("name"));
        assertEquals("GREENHOUSE", personio.get("ats_type"));
        assertTrue(personio.get("url").toString().contains("greenhouse.io"));
    }
}
