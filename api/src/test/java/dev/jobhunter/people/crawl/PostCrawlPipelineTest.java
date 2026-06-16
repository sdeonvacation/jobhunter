package dev.jobhunter.people.crawl;

import dev.jobhunter.model.JobPosting;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PostCrawlPipelineTest {

    @Test
    void run_executesHooksInOrder() {
        List<String> executionOrder = new ArrayList<>();

        PostCrawlHook hook1 = new PostCrawlHook() {
            @Override
            public void afterJobPersisted(JobPosting job, String rawHtml, Map<String, Object> rawJson) {
                executionOrder.add("hook1");
            }
            @Override
            public int order() { return 10; }
        };

        PostCrawlHook hook2 = new PostCrawlHook() {
            @Override
            public void afterJobPersisted(JobPosting job, String rawHtml, Map<String, Object> rawJson) {
                executionOrder.add("hook2");
            }
            @Override
            public int order() { return 5; }
        };

        PostCrawlPipeline pipeline = new PostCrawlPipeline(List.of(hook1, hook2));
        JobPosting job = JobPosting.builder().id(UUID.randomUUID()).build();

        pipeline.run(job, "<html>", Map.of());

        // hook2 (order=5) should run before hook1 (order=10)
        assertThat(executionOrder).containsExactly("hook2", "hook1");
    }

    @Test
    void run_continuesAfterHookException() {
        List<String> executionOrder = new ArrayList<>();

        PostCrawlHook failingHook = new PostCrawlHook() {
            @Override
            public void afterJobPersisted(JobPosting job, String rawHtml, Map<String, Object> rawJson) {
                throw new RuntimeException("Hook failed!");
            }
            @Override
            public int order() { return 1; }
        };

        PostCrawlHook successHook = new PostCrawlHook() {
            @Override
            public void afterJobPersisted(JobPosting job, String rawHtml, Map<String, Object> rawJson) {
                executionOrder.add("success");
            }
            @Override
            public int order() { return 2; }
        };

        PostCrawlPipeline pipeline = new PostCrawlPipeline(List.of(failingHook, successHook));
        JobPosting job = JobPosting.builder().id(UUID.randomUUID()).build();

        pipeline.run(job, "<html>", Map.of());

        // Second hook should still execute
        assertThat(executionOrder).containsExactly("success");
    }

    @Test
    void run_emptyHookList_doesNotThrow() {
        PostCrawlPipeline pipeline = new PostCrawlPipeline(List.of());
        JobPosting job = JobPosting.builder().id(UUID.randomUUID()).build();

        // Should not throw
        pipeline.run(job, "<html>", Map.of());
    }

    @Test
    void defaultOrder_isZero() {
        PostCrawlHook hook = new PostCrawlHook() {
            @Override
            public void afterJobPersisted(JobPosting job, String rawHtml, Map<String, Object> rawJson) {}
        };
        assertThat(hook.order()).isEqualTo(0);
    }
}
