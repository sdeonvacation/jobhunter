package dev.jobhunter.people.crawl;

import dev.jobhunter.model.JobPosting;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class PostCrawlPipeline {

    private final List<PostCrawlHook> hooks;

    public PostCrawlPipeline(List<PostCrawlHook> hooks) {
        this.hooks = hooks.stream()
                .sorted((a, b) -> Integer.compare(a.order(), b.order()))
                .toList();
    }

    public void run(JobPosting job, String rawHtml, Map<String, Object> rawJson) {
        for (PostCrawlHook hook : hooks) {
            try {
                hook.afterJobPersisted(job, rawHtml, rawJson);
            } catch (Exception e) {
                log.warn("PostCrawlHook {} failed for job {}: {}",
                        hook.getClass().getSimpleName(), job.getId(), e.getMessage());
            }
        }
    }
}
