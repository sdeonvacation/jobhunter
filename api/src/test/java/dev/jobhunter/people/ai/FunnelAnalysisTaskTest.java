package dev.jobhunter.people.ai;

import dev.jobhunter.people.service.FunnelAggregator.FunnelData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FunnelAnalysisTaskTest {

    private FunnelAnalysisTask task;

    @BeforeEach
    void setUp() {
        task = new FunnelAnalysisTask();
    }

    @Test
    void systemPrompt_containsBottleneckCategories() {
        FunnelData input = createSampleData();
        String prompt = task.systemPrompt(input);

        assertThat(prompt).contains("top-of-funnel");
        assertThat(prompt).contains("interviewing");
        assertThat(prompt).contains("closing");
        assertThat(prompt).contains("primaryBottleneck");
    }

    @Test
    void userPrompt_includesActualNumbers() {
        FunnelData input = new FunnelData(50, 10, 5, 3, 1,
                Map.of("application_to_screen", 0.2, "screen_to_technical", 0.5),
                Map.of("application_to_screen", 7.0));

        String prompt = task.userPrompt(input);

        assertThat(prompt).contains("Applications: 50");
        assertThat(prompt).contains("Recruiter screens: 10");
        assertThat(prompt).contains("Technical interviews: 5");
        assertThat(prompt).contains("Final rounds: 3");
        assertThat(prompt).contains("Offers: 1");
        assertThat(prompt).contains("20.0%");
        assertThat(prompt).contains("7.0 days");
    }

    @Test
    void parseResponse_validJson_parsesFunnelAnalysis() {
        String raw = """
                {
                  "primaryBottleneck": "top-of-funnel",
                  "explanation": "Only 20% of applications get a screen",
                  "suggestions": ["Improve resume keywords", "Target smaller companies"],
                  "stageInsights": {"application_to_screen": "Low match rate"}
                }""";

        FunnelAnalysisTask.FunnelAnalysis result = task.parseResponse(raw, createSampleData());

        assertThat(result.primaryBottleneck()).isEqualTo("top-of-funnel");
        assertThat(result.explanation()).contains("20%");
        assertThat(result.suggestions()).hasSize(2);
        assertThat(result.suggestions().get(0)).isEqualTo("Improve resume keywords");
        assertThat(result.stageInsights()).containsKey("application_to_screen");
    }

    @Test
    void parseResponse_jsonWithSurroundingText_extractsJson() {
        String raw = """
                Here is my analysis:
                
                {"primaryBottleneck": "closing", "explanation": "Final round to offer is weak", "suggestions": ["Negotiate better"], "stageInsights": {}}
                
                Let me know if you need more details.
                """;

        FunnelAnalysisTask.FunnelAnalysis result = task.parseResponse(raw, createSampleData());

        assertThat(result.primaryBottleneck()).isEqualTo("closing");
        assertThat(result.explanation()).contains("Final round");
    }

    @Test
    void parseResponse_invalidJson_returnsUnknownBottleneck() {
        String raw = "This is not JSON at all";

        FunnelAnalysisTask.FunnelAnalysis result = task.parseResponse(raw, createSampleData());

        assertThat(result.primaryBottleneck()).isEqualTo("unknown");
        assertThat(result.explanation()).contains("Failed to parse");
    }

    @Test
    void parseResponse_emptyResponse_returnsUnknown() {
        FunnelAnalysisTask.FunnelAnalysis result = task.parseResponse("", createSampleData());

        assertThat(result.primaryBottleneck()).isEqualTo("unknown");
    }

    @Test
    void parseResponse_nullResponse_returnsUnknown() {
        FunnelAnalysisTask.FunnelAnalysis result = task.parseResponse(null, createSampleData());

        assertThat(result.primaryBottleneck()).isEqualTo("unknown");
    }

    @Test
    void parseResponse_missingFields_usesDefaults() {
        String raw = """
                {"primaryBottleneck": "interviewing"}""";

        FunnelAnalysisTask.FunnelAnalysis result = task.parseResponse(raw, createSampleData());

        assertThat(result.primaryBottleneck()).isEqualTo("interviewing");
        assertThat(result.explanation()).isEmpty();
        assertThat(result.suggestions()).isEmpty();
        assertThat(result.stageInsights()).isEmpty();
    }

    @Test
    void userPrompt_handlesEmptyMaps() {
        FunnelData input = new FunnelData(0, 0, 0, 0, 0,
                new LinkedHashMap<>(), new LinkedHashMap<>());

        String prompt = task.userPrompt(input);

        assertThat(prompt).contains("Applications: 0");
        assertThat(prompt).contains("Offers: 0");
    }

    private FunnelData createSampleData() {
        Map<String, Double> rates = new LinkedHashMap<>();
        rates.put("application_to_screen", 0.2);
        rates.put("screen_to_technical", 0.5);
        rates.put("technical_to_final", 0.6);
        rates.put("final_to_offer", 0.33);

        Map<String, Double> avgDays = new LinkedHashMap<>();
        avgDays.put("application_to_screen", 7.0);
        avgDays.put("screen_to_technical", 5.0);

        return new FunnelData(50, 10, 5, 3, 1, rates, avgDays);
    }
}
