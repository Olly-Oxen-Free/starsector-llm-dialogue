package starlogue.llm;

import java.util.List;

public class LLMResponse {
    public final String content;
    public final List<LLMToolCall> toolCalls;

    public LLMResponse(String content, List<LLMToolCall> toolCalls) {
        this.content = content != null ? content : "";
        this.toolCalls = toolCalls != null ? toolCalls : List.of();
    }
}
