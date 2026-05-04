package starlogue.llm;

import java.util.List;

public class LLMResponse {
    public final String content;
    public final List<LLMToolCall> toolCalls;
    /** Provider-native reasoning / chain-of-thought text when exposed by the API; otherwise null. */
    public final String reasoning;
    /** Optional compact usage summary (e.g. JSON string of token counts). */
    public final String usageSummary;

    public LLMResponse(String content, List<LLMToolCall> toolCalls) {
        this(content, toolCalls, null, null);
    }

    public LLMResponse(String content, List<LLMToolCall> toolCalls, String reasoning, String usageSummary) {
        this.content = content != null ? content : "";
        this.toolCalls = toolCalls != null ? toolCalls : List.of();
        this.reasoning = reasoning;
        this.usageSummary = usageSummary;
    }
}
