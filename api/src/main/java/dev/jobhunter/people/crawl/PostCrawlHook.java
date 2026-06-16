package dev.jobhunter.people.crawl;

import dev.jobhunter.model.JobPosting;

import java.util.Map;

public interface PostCrawlHook {

    void afterJobPersisted(JobPosting job, String rawHtml, Map<String, Object> rawJson);

    default int order() {
        return 0;
    }
}
