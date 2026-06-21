package dev.jobhunter.dto;

import dev.jobhunter.model.*;
import dev.jobhunter.model.enums.SkillCategory;
import dev.jobhunter.service.PersonalProfile;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps domain entities to DTOs. Stateless utility.
 */
public final class DtoMapper {

    private DtoMapper() {}

    public static JobSummaryDto toJobSummary(JobPosting job) {
        MatchScore match = job.getMatchScore();
        OpportunityScore opp = job.getOpportunityScore();
        List<String> topSkills = job.getSkills() != null
                ? job.getSkills().stream()
                    .filter(JobSkill::isRequired)
                    .map(JobSkill::getSkillName)
                    .limit(5)
                    .toList()
                : List.of();

        return new JobSummaryDto(
                job.getId(),
                job.getTitle(),
                job.getCompany() != null ? job.getCompany().getName() : null,
                job.getLocation(),
                job.getIsRemote() != null ? job.getIsRemote().name() : null,
                opp != null ? opp.getScore() : 0,
                match != null ? match.getOverallScore() : 0,
                match != null && match.getRecommendation() != null ? match.getRecommendation().name() : null,
                topSkills,
                job.getSalaryMin(),
                job.getSalaryMax(),
                job.getSalaryCurrency(),
                job.getPostedDate(),
                job.getSource() != null ? job.getSource().name() : null,
                job.getApplyUrl(),
                job.isApplied(),
                job.getExternalLinks(),
                job.getVisaSponsorship() != null ? job.getVisaSponsorship().name() : null
        );
    }

    public static JobDetailDto toJobDetail(JobPosting job, List<JobSkill> skills) {
        MatchScore match = job.getMatchScore();
        OpportunityScore opp = job.getOpportunityScore();

        TechStackDto techStack = toTechStack(skills);
        ScoreBreakdownDto scoreBreakdown = toScoreBreakdown(job);

        List<String> matchedSkills = match != null && match.getMatchedSkills() != null
                ? match.getMatchedSkills() : List.of();
        List<String> missingSkills = match != null && match.getMissingSkills() != null
                ? match.getMissingSkills() : List.of();

        return new JobDetailDto(
                job.getId(),
                job.getTitle(),
                job.getCompany() != null ? job.getCompany().getName() : null,
                job.getCompany() != null ? job.getCompany().getId() : null,
                job.getLocation(),
                job.getLocationCity(),
                job.getLocationCountry(),
                job.getIsRemote() != null ? job.getIsRemote().name() : null,
                job.getEmploymentType() != null ? job.getEmploymentType().name() : null,
                job.getDescription(),
                job.getApplyUrl(),
                job.getPostedDate(),
                job.getDiscoveredDate(),
                job.getSalaryMin(),
                job.getSalaryMax(),
                job.getSalaryCurrency(),
                job.getSalaryPeriod() != null ? job.getSalaryPeriod().name() : null,
                job.getSource() != null ? job.getSource().name() : null,
                job.getExternalId(),
                job.getEndpoint() != null && job.getEndpoint().getAtsType() != null
                        ? job.getEndpoint().getAtsType().name() : null,
                opp != null ? opp.getScore() : 0,
                match != null ? match.getOverallScore() : 0,
                match != null && match.getRecommendation() != null ? match.getRecommendation().name() : null,
                matchedSkills,
                missingSkills,
                techStack,
                job.getRecruiterName(),
                job.getRecruiterEmail(),
                scoreBreakdown,
                job.getVisaSponsorship() != null ? job.getVisaSponsorship().name() : null
        );
    }

    public static TechStackDto toTechStack(List<JobSkill> skills) {
        Map<SkillCategory, List<String>> grouped = skills.stream()
                .collect(Collectors.groupingBy(
                        JobSkill::getCategory,
                        Collectors.mapping(JobSkill::getSkillName, Collectors.toList())
                ));

        return new TechStackDto(
                grouped.getOrDefault(SkillCategory.LANGUAGE, List.of()),
                grouped.getOrDefault(SkillCategory.FRAMEWORK, List.of()),
                grouped.getOrDefault(SkillCategory.DATABASE, List.of()),
                grouped.getOrDefault(SkillCategory.CLOUD, List.of()),
                grouped.getOrDefault(SkillCategory.TOOL, List.of()),
                grouped.getOrDefault(SkillCategory.METHODOLOGY, List.of())
        );
    }

    public static ScoreBreakdownDto toScoreBreakdown(JobPosting job) {
        MatchScore match = job.getMatchScore();
        OpportunityScore opp = job.getOpportunityScore();

        @SuppressWarnings("unchecked")
        Map<String, Integer> breakdown = opp != null && opp.getBreakdown() != null
                ? (Map<String, Integer>) opp.getBreakdown()
                : Map.of();

        return new ScoreBreakdownDto(
                job.getId(),
                opp != null ? opp.getScore() : 0,
                match != null ? match.getOverallScore() : 0,
                match != null && match.getRecommendation() != null ? match.getRecommendation().name() : null,
                breakdown
        );
    }

    public static CompanySummaryDto toCompanySummary(Company company) {
        int endpointCount = 0;
        try {
            if (company.getCareerEndpoints() != null) {
                endpointCount = company.getCareerEndpoints().size();
            }
        } catch (Exception ignored) {
            // Lazy loading may fail outside transaction
        }

        return new CompanySummaryDto(
                company.getId(),
                company.getName(),
                company.getDomain(),
                company.getCountry(),
                company.getStatus() != null ? company.getStatus().name() : null,
                company.getPriorityScore(),
                endpointCount,
                company.getInterviewRate(),
                company.getTotalApplications()
        );
    }

    public static CompanyDetailDto toCompanyDetail(Company company, int activeJobCount) {
        List<CompanyDetailDto.EndpointDto> endpoints = company.getCareerEndpoints() != null
                ? company.getCareerEndpoints().stream()
                    .map(ep -> new CompanyDetailDto.EndpointDto(
                            ep.getId(),
                            ep.getUrl(),
                            ep.getAtsType() != null ? ep.getAtsType().name() : null,
                            ep.isActive(),
                            ep.getLastCrawlStatus() != null ? ep.getLastCrawlStatus().name() : null,
                            ep.getLastCrawledAt()
                    ))
                    .toList()
                : List.of();

        return new CompanyDetailDto(
                company.getId(),
                company.getName(),
                company.getDomain(),
                company.getCountry(),
                company.getStatus() != null ? company.getStatus().name() : null,
                company.getDiscoveredVia() != null ? company.getDiscoveredVia().name() : null,
                company.getDiscoveredAt(),
                company.getPriorityScore(),
                company.getAvgMatchScore(),
                company.getInterviewRate(),
                company.getTotalApplications(),
                company.getTotalInterviews(),
                endpoints,
                activeJobCount
        );
    }

    public static ApplicationDto toApplication(Application app, List<JobOutcome> outcomes) {
        List<ApplicationDto.OutcomeDto> outcomeDtos = outcomes != null
                ? outcomes.stream()
                    .map(o -> new ApplicationDto.OutcomeDto(
                            o.getId(),
                            o.getStage() != null ? o.getStage().name() : null,
                            o.getOccurredAt() != null ? o.getOccurredAt().atStartOfDay() : null,
                            o.getNotes()
                    ))
                    .toList()
                : List.of();

        JobPosting job = app.getJob();
        return new ApplicationDto(
                app.getId(),
                job != null ? job.getId() : null,
                job != null ? job.getTitle() : null,
                job != null && job.getCompany() != null ? job.getCompany().getName() : null,
                app.getStatus() != null ? app.getStatus().name() : null,
                app.getAppliedDate(),
                app.getResumeVariant(),
                app.getNotes(),
                outcomeDtos,
                app.getCreatedAt()
        );
    }

    public static ProfileDto toProfile(PersonalProfile profile) {
        List<ProfileDto.SkillDto> skills = profile.skills() != null
                ? profile.skills().stream()
                    .map(s -> new ProfileDto.SkillDto(s.name(), s.proficiency(), s.category()))
                    .toList()
                : List.of();

        ProfileDto.PreferencesDto prefs = profile.preferences() != null
                ? new ProfileDto.PreferencesDto(
                    profile.preferences().locations(),
                    profile.preferences().employmentType(),
                    profile.preferences().minSalaryEur(),
                    profile.preferences().seniority(),
                    profile.preferences().languages(),
                    profile.preferences().excludedIndustries()
                )
                : null;

        return new ProfileDto(
                profile.name(),
                profile.title(),
                profile.yearsOfExperience(),
                skills,
                prefs
        );
    }
}
