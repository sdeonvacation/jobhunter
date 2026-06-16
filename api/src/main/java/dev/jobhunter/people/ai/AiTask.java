package dev.jobhunter.people.ai;

public interface AiTask<I, O> {
    String systemPrompt(I input);
    String userPrompt(I input);
    O parseResponse(String raw, I input);
}
