package dev.jobhunter.config;

import dev.jobhunter.scheduler.AiCrawlScheduler;
import dev.jobhunter.scheduler.DigestScheduler;
import dev.jobhunter.scheduler.DiscoveryScheduler;
import dev.jobhunter.scheduler.GdprPurgeScheduler;
import dev.jobhunter.scheduler.PipelineScheduler;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QuartzConfig {

    // --- Pipeline (Crawl → LinkedIn → Scoring) ---

    @Bean
    public JobDetail pipelineJobDetail() {
        return JobBuilder.newJob(PipelineScheduler.class)
                .withIdentity("pipelineJob", "pipeline")
                .storeDurably()
                .requestRecovery(true)
                .build();
    }

    @Bean
    public Trigger pipelineTrigger(
            JobDetail pipelineJobDetail,
            @Value("${pipeline.schedule:0 0 */6 * * ?}") String cronExpression
    ) {
        return TriggerBuilder.newTrigger()
                .forJob(pipelineJobDetail)
                .withIdentity("pipelineTrigger", "pipeline")
                .withSchedule(
                        CronScheduleBuilder.cronSchedule(cronExpression)
                                .withMisfireHandlingInstructionFireAndProceed()
                )
                .build();
    }

    @Bean
    public Trigger pipelineStartupTrigger(JobDetail pipelineJobDetail) {
        return TriggerBuilder.newTrigger()
                .forJob(pipelineJobDetail)
                .withIdentity("pipelineStartupTrigger", "pipeline")
                .startAt(new java.util.Date(System.currentTimeMillis() + 10_000))
                .build();
    }

    // --- Discovery ---

    @Bean
    public JobDetail discoveryJobDetail() {
        return JobBuilder.newJob(DiscoveryScheduler.class)
                .withIdentity("discoveryJob", "discovery")
                .storeDurably()
                .requestRecovery(true)
                .build();
    }

    @Bean
    public Trigger discoveryTrigger(
            JobDetail discoveryJobDetail,
            @Value("${discovery.schedule:0 0 6 * * ?}") String cronExpression
    ) {
        return TriggerBuilder.newTrigger()
                .forJob(discoveryJobDetail)
                .withIdentity("discoveryTrigger", "discovery")
                .withSchedule(
                        CronScheduleBuilder.cronSchedule(cronExpression)
                                .withMisfireHandlingInstructionFireAndProceed()
                )
                .build();
    }

    // --- Daily Digest ---

    @Bean
    public JobDetail digestJobDetail() {
        return JobBuilder.newJob(DigestScheduler.class)
                .withIdentity("digestJob", "digest")
                .storeDurably()
                .requestRecovery(true)
                .build();
    }

    @Bean
    public Trigger digestTrigger(
            JobDetail digestJobDetail,
            @Value("${digest.schedule:0 30 7 * * ?}") String cronExpression
    ) {
        return TriggerBuilder.newTrigger()
                .forJob(digestJobDetail)
                .withIdentity("digestTrigger", "digest")
                .withSchedule(
                        CronScheduleBuilder.cronSchedule(cronExpression)
                                .withMisfireHandlingInstructionFireAndProceed()
                )
                .build();
    }

    // --- GDPR Purge ---

    @Bean
    public JobDetail gdprPurgeJobDetail() {
        return JobBuilder.newJob(GdprPurgeScheduler.class)
                .withIdentity("gdprPurgeJob", "gdpr")
                .storeDurably()
                .requestRecovery(true)
                .build();
    }

    @Bean
    public Trigger gdprPurgeTrigger(
            JobDetail gdprPurgeJobDetail,
            @Value("${gdpr.purge-schedule:0 0 2 * * ?}") String cronExpression
    ) {
        return TriggerBuilder.newTrigger()
                .forJob(gdprPurgeJobDetail)
                .withIdentity("gdprPurgeTrigger", "gdpr")
                .withSchedule(
                        CronScheduleBuilder.cronSchedule(cronExpression)
                                .withMisfireHandlingInstructionFireAndProceed()
                )
                .build();
    }

    // --- AI Crawl (CUSTOM endpoints) ---

    @Bean
    public JobDetail aiCrawlJobDetail() {
        return JobBuilder.newJob(AiCrawlScheduler.class)
                .withIdentity("aiCrawlJob", "ai-crawl")
                .storeDurably()
                .requestRecovery(true)
                .build();
    }

    @Bean
    public Trigger aiCrawlTrigger(
            JobDetail aiCrawlJobDetail,
            @Value("${ai-crawl.schedule:0 0 3 * * ?}") String cronExpression
    ) {
        return TriggerBuilder.newTrigger()
                .forJob(aiCrawlJobDetail)
                .withIdentity("aiCrawlTrigger", "ai-crawl")
                .withSchedule(
                        CronScheduleBuilder.cronSchedule(cronExpression)
                                .withMisfireHandlingInstructionFireAndProceed()
                )
                .build();
    }
}
