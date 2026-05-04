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
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * Subclass hook for provider-specific HTTP headers (e.g. OpenRouter's
     * HTTP-Referer / X-Title attribution pair). Default: no extras.
     * Returned entries are added verbatim before the request is sent.
     */
    protected Map<String, String> extraHeaders() {
        return Collections.emptyMap();
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

        Map<String, String> extras = extraHeaders();
        if (extras != null) {
            for (Map.Entry<String, String> e : extras.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    builder.header(e.getKey(), e.getValue());
                }
            }
        }

        log.debug("Starlogue → LLM endpoint: " + endpoint);

        HttpResponse<String> response = http.send(builder.build(),
                                                  HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            String responseText = response.body();
            String msg = responseText;
            String providerName = null;
            String providerRaw = null;
            try {
                JSONObject errRoot = new JSONObject(responseText);
                if (errRoot.has("error")) {
                    Object err = errRoot.get("error");
                    if (err instanceof JSONObject) {
                        JSONObject errObj = (JSONObject) err;
                        String m = errObj.optString("message", null);
                        if (m != null && !m.isEmpty()) {
                            msg = m;
                        }
                        Object metadataObj = errObj.opt("metadata");
                        if (metadataObj instanceof JSONObject) {
                            JSONObject meta = (JSONObject) metadataObj;
                            providerName = meta.optString("provider_name", null);
                            providerRaw = meta.optString("raw", null);
                        }
                    } else {
                        String m = errRoot.optString("error", null);
                        if (m != null && !m.isEmpty() && m.length() < 500) {
                            msg = m;
                        }
                    }
                }
            } catch (Throwable ignored) { }
            StringBuilder detail = new StringBuilder();
            if (providerName != null && !providerName.isEmpty()) {
                detail.append(" [provider=").append(providerName).append("]");
            }
            if (providerRaw != null && !providerRaw.isEmpty()) {
                String raw = providerRaw.trim();
                if (raw.length() > 220) raw = raw.substring(0, 220) + "...";
                detail.append(" [providerRaw=").append(raw).append("]");
            }
            throw new RuntimeException("LLM backend returned HTTP " + response.statusCode() + ": " + msg + detail
                + (msg.toLowerCase().contains("not found")
                    ? " (if using Ollama, run: ollama pull <model> for the model name in Luna or Starlogue_credentials.json)"
                    : ""));
        }

        JSONObject root = new JSONObject(response.body());
        String content = ToolCallParser.parseContent(root);
        List<LLMToolCall> toolCalls = ToolCallParser.parseOpenAI(root);
        String reasoning = ToolCallParser.parseOpenAIReasoning(root);
        String usage = ToolCallParser.parseOpenAIUsageSummary(root);
        return new LLMResponse(content, toolCalls, reasoning, usage);
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
