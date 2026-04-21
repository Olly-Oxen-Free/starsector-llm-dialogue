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

public class OpenAIClient implements LLMClient {

    private static final Logger log = Logger.getLogger(OpenAIClient.class);

    private final String endpoint;
    private final String apiKey;
    private final HttpClient http;

    public OpenAIClient(String baseUrl, String apiKey) {
        // Normalise endpoint to always point at /chat/completions
        String url = baseUrl.trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        this.endpoint = url.endsWith("/chat/completions") ? url : url + "/chat/completions";
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public LLMResponse complete(LLMRequest request) throws Exception {
        JSONObject body = buildRequestBody(request);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()));

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        log.debug("Starlogue → LLM endpoint: " + endpoint);

        HttpResponse<String> response = http.send(builder.build(),
                                                  HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("LLM backend returned HTTP " + response.statusCode()
                + ": " + response.body());
        }

        JSONObject root = new JSONObject(response.body());
        String content = ToolCallParser.parseContent(root);
        List<LLMToolCall> toolCalls = ToolCallParser.parseOpenAI(root);
        return new LLMResponse(content, toolCalls);
    }

    private JSONObject buildRequestBody(LLMRequest request) throws org.json.JSONException {
        JSONObject body = new JSONObject();
        body.put("model", request.model);
        body.put("temperature", (double) request.temperature);
        body.put("max_tokens", request.maxTokens);

        JSONArray messages = new JSONArray();
        for (Map<String, Object> msg : request.messages) {
            messages.put(mapToJson(msg));
        }
        body.put("messages", messages);

        if (request.tools != null && !request.tools.isEmpty()) {
            JSONArray tools = new JSONArray();
            for (Map<String, Object> tool : request.tools) {
                tools.put(deepToJson(tool));
            }
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        return body;
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
