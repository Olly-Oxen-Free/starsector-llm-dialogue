package starlogue.llm;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * LLMClient implementation for the Anthropic Messages API.
 *
 * <p>The Anthropic format differs from OpenAI in three ways:
 * <ul>
 *   <li>System prompt is a top-level field, not a messages array entry.</li>
 *   <li>Tool definitions use {@code input_schema} not {@code parameters}.</li>
 *   <li>Response content is a typed block array, not a choices structure.</li>
 * </ul>
 *
 * <p>Configure via {@code saves/common/Starlogue_credentials.json}: set
 * {@code starlogue_provider} to {@code anthropic} and {@code starlogue_api_key}
 * to your Anthropic key.
 * {@code starlogue_model} should be a valid Anthropic model ID
 * (e.g. {@code claude-sonnet-4-6}).
 */
public class AnthropicClient implements LLMClient {

    private static final Logger log = Logger.getLogger(AnthropicClient.class);

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** Shared HttpClient — allocated once per JVM lifetime. Thread-safe per Java spec. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final String apiKey;

    public AnthropicClient(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public LLMResponse complete(LLMRequest request) throws Exception {
        JSONObject body = buildRequestBody(request);

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(ENDPOINT))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        log.debug("Starlogue → Anthropic endpoint: " + ENDPOINT);

        HttpResponse<String> response = HTTP.send(httpRequest,
                                                  HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Anthropic API returned HTTP " + response.statusCode()
                + ": " + response.body());
        }

        JSONObject root = new JSONObject(response.body());
        String content = ToolCallParser.parseAnthropicContent(root);
        List<LLMToolCall> toolCalls = ToolCallParser.parseAnthropicToolCalls(root);
        String thinking = ToolCallParser.parseAnthropicThinking(root);
        return new LLMResponse(content, toolCalls, thinking, null);
    }

    private JSONObject buildRequestBody(LLMRequest request) throws org.json.JSONException {
        JSONObject body = new JSONObject();
        body.put("model", request.model);
        body.put("max_tokens", request.maxTokens);
        // Anthropic temperature is 0.0–1.0; clamp to be safe
        body.put("temperature", Math.min(1.0, (double) request.temperature));

        // Extract system message — Anthropic takes it as a top-level field
        String systemPrompt = null;
        List<Map<String, Object>> userMessages = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> msg : request.messages) {
            Object role = msg.get("role");
            if ("system".equals(role)) {
                systemPrompt = String.valueOf(msg.get("content"));
            } else {
                userMessages.add(msg);
            }
        }
        if (systemPrompt != null) {
            body.put("system", systemPrompt);
        }

        // Messages (user/assistant only)
        JSONArray messages = new JSONArray();
        for (Map<String, Object> msg : userMessages) {
            JSONObject jMsg = new JSONObject();
            jMsg.put("role", msg.get("role"));
            jMsg.put("content", String.valueOf(msg.get("content")));
            messages.put(jMsg);
        }
        body.put("messages", messages);

        // Tools — convert from OpenAI format to Anthropic format
        if (request.tools != null && !request.tools.isEmpty()) {
            JSONArray tools = new JSONArray();
            for (Map<String, Object> openAiTool : request.tools) {
                tools.put(convertTool(openAiTool));
            }
            body.put("tools", tools);
        }

        return body;
    }

    /**
     * Converts an OpenAI-format tool definition to Anthropic format.
     *
     * <p>OpenAI: {@code {type: "function", function: {name, description, parameters}}}
     * <p>Anthropic: {@code {name, description, input_schema}}
     */
    @SuppressWarnings("unchecked")
    private JSONObject convertTool(Map<String, Object> openAiTool) throws org.json.JSONException {
        JSONObject anthropicTool = new JSONObject();
        Object fnObj = openAiTool.get("function");
        if (!(fnObj instanceof Map)) return anthropicTool;

        Map<String, Object> fn = (Map<String, Object>) fnObj;
        anthropicTool.put("name", fn.get("name"));
        anthropicTool.put("description", fn.get("description"));

        // parameters → input_schema (same JSON Schema object)
        Object params = fn.get("parameters");
        if (params instanceof Map) {
            anthropicTool.put("input_schema", deepToJson((Map<String, Object>) params));
        } else {
            // Empty schema fallback
            JSONObject emptySchema = new JSONObject();
            emptySchema.put("type", "object");
            emptySchema.put("properties", new JSONObject());
            anthropicTool.put("input_schema", emptySchema);
        }

        return anthropicTool;
    }

    private JSONObject mapToJson(Map<String, Object> map) throws org.json.JSONException {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            obj.put(e.getKey(), deepToJson(e.getValue()));
        }
        return obj;
    }

    @SuppressWarnings("unchecked")
    private Object deepToJson(Object value) throws org.json.JSONException {
        if (value instanceof Map) {
            return mapToJson((Map<String, Object>) value);
        } else if (value instanceof List) {
            JSONArray arr = new JSONArray();
            for (Object item : (List<?>) value) {
                arr.put(deepToJson(item));
            }
            return arr;
        }
        return value;
    }
}
