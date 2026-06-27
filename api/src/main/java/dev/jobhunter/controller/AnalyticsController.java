package dev.jobhunter.controller;

import dev.jobhunter.dto.PatternAnalyticsDto;
import dev.jobhunter.service.PatternAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final PatternAnalysisService patternAnalysisService;

    @GetMapping("/patterns")
    public PatternAnalyticsDto getPatterns(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate since) {

        LocalDate effectiveSince = since != null ? since : LocalDate.now().minusDays(90);
        return patternAnalysisService.analyzePatterns(effectiveSince);
    }
}
