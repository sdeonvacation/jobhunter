package dev.jobhunter.people.service;

import dev.jobhunter.model.Application;
import dev.jobhunter.model.JobOutcome;
import dev.jobhunter.model.enums.OutcomeStage;
import dev.jobhunter.people.model.enums.InterviewSource;
import dev.jobhunter.repository.ApplicationRepository;
import dev.jobhunter.repository.JobOutcomeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class FunnelAggregator {

    private final ApplicationRepository applicationRepository;
    private final JobOutcomeRepository jobOutcomeRepository;

    public FunnelAggregator(ApplicationRepository applicationRepository,
                            JobOutcomeRepository jobOutcomeRepository) {
        this.applicationRepository = applicationRepository;
        this.jobOutcomeRepository = jobOutcomeRepository;
    }

    public FunnelData aggregate(LocalDate from, LocalDate to) {
        List<Application> allApps = applicationRepository.findAll().stream()
                .filter(a -> a.getAppliedDate() != null)
                .filter(a -> !a.getAppliedDate().isBefore(from) && !a.getAppliedDate().isAfter(to))
                .toList();

        List<JobOutcome> allOutcomes = allApps.stream()
                .flatMap(app -> jobOutcomeRepository.findByApplicationId(app.getId()).stream())
                .toList();

        return buildFunnelData(allApps, allOutcomes);
    }

    public Map<InterviewSource, FunnelData> aggregateBySource(LocalDate from, LocalDate to) {
        List<Application> allApps = applicationRepository.findAll().stream()
                .filter(a -> a.getAppliedDate() != null)
                .filter(a -> !a.getAppliedDate().isBefore(from) && !a.getAppliedDate().isAfter(to))
                .toList();

        Map<InterviewSource, List<Application>> grouped = allApps.stream()
                .collect(Collectors.groupingBy(
                        app -> app.getInterviewSource() != null ? app.getInterviewSource() : InterviewSource.APPLICATION
                ));

        Map<InterviewSource, FunnelData> result = new EnumMap<>(InterviewSource.class);
        for (Map.Entry<InterviewSource, List<Application>> entry : grouped.entrySet()) {
            List<JobOutcome> outcomes = entry.getValue().stream()
                    .flatMap(app -> jobOutcomeRepository.findByApplicationId(app.getId()).stream())
                    .toList();
            result.put(entry.getKey(), buildFunnelData(entry.getValue(), outcomes));
        }
        return result;
    }

    private FunnelData buildFunnelData(List<Application> apps, List<JobOutcome> outcomes) {
        int applications = apps.size();

        int recruiterScreen = countByStage(outcomes, OutcomeStage.PHONE_SCREEN);
        int technical = countByStage(outcomes, OutcomeStage.INTERVIEW_1);
        int finalRound = countByStage(outcomes, OutcomeStage.INTERVIEW_2);
        int offers = countByStage(outcomes, OutcomeStage.OFFER);

        Map<String, Double> conversionRates = new LinkedHashMap<>();
        conversionRates.put("application_to_screen", safeRate(recruiterScreen, applications));
        conversionRates.put("screen_to_technical", safeRate(technical, recruiterScreen));
        conversionRates.put("technical_to_final", safeRate(finalRound, technical));
        conversionRates.put("final_to_offer", safeRate(offers, finalRound));
        conversionRates.put("application_to_offer", safeRate(offers, applications));

        Map<String, Double> avgDaysBetweenStages = computeAvgDays(apps, outcomes);

        return new FunnelData(applications, recruiterScreen, technical, finalRound, offers,
                conversionRates, avgDaysBetweenStages);
    }

    private int countByStage(List<JobOutcome> outcomes, OutcomeStage stage) {
        return (int) outcomes.stream()
                .filter(o -> o.getStage() == stage)
                .map(o -> o.getApplication().getId())
                .distinct()
                .count();
    }

    private double safeRate(int numerator, int denominator) {
        if (denominator == 0) return 0.0;
        return Math.round((double) numerator / denominator * 1000.0) / 1000.0;
    }

    private Map<String, Double> computeAvgDays(List<Application> apps, List<JobOutcome> outcomes) {
        Map<String, Double> avgDays = new LinkedHashMap<>();

        Map<UUID, LocalDate> appDates = apps.stream()
                .collect(Collectors.toMap(Application::getId, Application::getAppliedDate));

        Map<UUID, Map<OutcomeStage, LocalDate>> outcomesByApp = outcomes.stream()
                .filter(o -> o.getOccurredAt() != null)
                .collect(Collectors.groupingBy(
                        o -> o.getApplication().getId(),
                        Collectors.toMap(JobOutcome::getStage, JobOutcome::getOccurredAt, (a, b) -> a)
                ));

        avgDays.put("application_to_screen", avgDaysBetween(appDates, outcomesByApp, null, OutcomeStage.PHONE_SCREEN));
        avgDays.put("screen_to_technical", avgDaysBetween(appDates, outcomesByApp, OutcomeStage.PHONE_SCREEN, OutcomeStage.INTERVIEW_1));
        avgDays.put("technical_to_final", avgDaysBetween(appDates, outcomesByApp, OutcomeStage.INTERVIEW_1, OutcomeStage.INTERVIEW_2));
        avgDays.put("final_to_offer", avgDaysBetween(appDates, outcomesByApp, OutcomeStage.INTERVIEW_2, OutcomeStage.OFFER));

        return avgDays;
    }

    private double avgDaysBetween(Map<UUID, LocalDate> appDates,
                                  Map<UUID, Map<OutcomeStage, LocalDate>> outcomesByApp,
                                  OutcomeStage fromStage,
                                  OutcomeStage toStage) {
        List<Long> gaps = new ArrayList<>();

        for (Map.Entry<UUID, Map<OutcomeStage, LocalDate>> entry : outcomesByApp.entrySet()) {
            UUID appId = entry.getKey();
            Map<OutcomeStage, LocalDate> stages = entry.getValue();

            LocalDate toDate = stages.get(toStage);
            if (toDate == null) continue;

            LocalDate fromDate;
            if (fromStage == null) {
                fromDate = appDates.get(appId);
            } else {
                fromDate = stages.get(fromStage);
            }

            if (fromDate != null) {
                long days = ChronoUnit.DAYS.between(fromDate, toDate);
                if (days >= 0) {
                    gaps.add(days);
                }
            }
        }

        if (gaps.isEmpty()) return 0.0;
        return Math.round(gaps.stream().mapToLong(Long::longValue).average().orElse(0.0) * 10.0) / 10.0;
    }

    public record FunnelData(
            int applications,
            int recruiterScreen,
            int technical,
            int finalRound,
            int offers,
            Map<String, Double> conversionRates,
            Map<String, Double> avgDaysBetweenStages
    ) {}
}
