package starlogue.llm;

import java.util.List;
import java.util.Map;

public class LLMRequest {
    public final List<Map<String, Object>> messages;
    public final List<Map<String, Object>> tools;
    public final String model;
    public final float temperature;
    public final int maxTokens;

    public LLMRequest(List<Map<String, Object>> messages,
                      List<Map<String, Object>> tools,
                      String model, float temperature, int maxTokens) {
        this.messages = messages;
        this.tools = tools;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }
}
