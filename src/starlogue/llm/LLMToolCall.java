package starlogue.llm;

import java.util.Map;

public class LLMToolCall {
    public final String toolId;
    public final Map<String, Object> args;

    public LLMToolCall(String toolId, Map<String, Object> args) {
        this.toolId = toolId;
        this.args = args;
    }

    @Override
    public String toString() {
        return "LLMToolCall{toolId='" + toolId + "', args=" + args + "}";
    }
}
