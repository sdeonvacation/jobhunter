package dev.jobhunter.people.ai;

import java.util.UUID;

public record GeneratedMessage(
        String content,
        MessageVariant variant,
        UUID contactId,
        UUID jobId,
        String modelUsed,
        int tokensUsed
) {
}
