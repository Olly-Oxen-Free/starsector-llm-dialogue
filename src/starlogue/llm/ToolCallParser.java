package starlogue.llm;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public class ToolCallParser {

    private static final org.apache.log4j.Logger log =
        org.apache.log4j.Logger.getLogger(ToolCallParser.class);

    /**
     * Parse tool_calls from an OpenAI /v1/chat/completions response.
     * Returns empty list if no tool_calls present.
     */
    public static List<LLMToolCall> parseOpenAI(JSONObject root) {
        List<LLMToolCall> result = new ArrayList<>();
        try {
            JSONObject message = root.getJSONArray("choices")
                                     .getJSONObject(0)
                                     .getJSONObject("message");
            if (!message.has("tool_calls")) return result;
            JSONArray toolCalls = message.getJSONArray("tool_calls");
            for (int i = 0; i < toolCalls.length(); i++) {
                JSONObject tc = toolCalls.getJSONObject(i);
                JSONObject fn = tc.getJSONObject("function");
                String name = fn.getString("name");
                String argsStr = fn.optString("arguments", "{}");
                JSONObject argsJson;
                try {
                    argsJson = (argsStr == null || argsStr.trim().isEmpty())
                        ? new JSONObject()
                        : new JSONObject(argsStr);
                } catch (Exception badArgs) {
                    // Keep the tool call rather than dropping the whole response.
                    argsJson = new JSONObject();
                }
                result.add(new LLMToolCall(name, jsonObjectToMap(argsJson)));
            }
        } catch (Exception e) {
            log.warn("Starlogue: failed to parse tool_calls from LLM response: " + e.getMessage());
        }
        return result;
    }

    /**
     * Extract content string from an OpenAI response.
     * Returns null if the message has no content (tool-call-only responses).
     */
    public static String parseContent(JSONObject root) {
        try {
            JSONObject message = root.getJSONArray("choices")
                                     .getJSONObject(0)
                                     .getJSONObject("message");
            String content = message.optString("content", null);
            return (content == null || content.isEmpty()) ? null : content;
        } catch (Exception e) {
            return null;
        }
    }

    /** Some OpenAI-compatible models return a separate reasoning field on the message. */
    public static String parseOpenAIReasoning(JSONObject root) {
        try {
            JSONObject message = root.getJSONArray("choices")
                                     .getJSONObject(0)
                                     .getJSONObject("message");
            if (message.has("reasoning")) {
                String r = message.optString("reasoning", null);
                if (r != null && !r.isEmpty()) return r;
            }
            if (message.has("reasoning_content")) {
                String r = message.optString("reasoning_content", null);
                if (r != null && !r.isEmpty()) return r;
            }
        } catch (Exception ignored) { }
        return null;
    }

    public static String parseOpenAIUsageSummary(JSONObject root) {
        try {
            if (!root.has("usage")) return null;
            Object u = root.get("usage");
            return u != null ? String.valueOf(u) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Anthropic /v1/messages ────────────────────────────────────────────

    /**
     * Extract concatenated text from an Anthropic Messages API response.
     * Content is an array of typed blocks; we join all text blocks.
     */
    public static String parseAnthropicContent(JSONObject root) {
        try {
            JSONArray content = root.getJSONArray("content");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.getJSONObject(i);
                if ("text".equals(block.optString("type"))) {
                    String text = block.optString("text", "");
                    if (!text.isEmpty()) {
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(text);
                    }
                }
            }
            String result = sb.toString().trim();
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract tool_use blocks from an Anthropic Messages API response.
     * Each tool_use block becomes an LLMToolCall.
     */
    /**
     * Anthropic extended thinking: {@code type == "thinking"} blocks joined.
     */
    public static String parseAnthropicThinking(JSONObject root) {
        try {
            JSONArray content = root.getJSONArray("content");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.getJSONObject(i);
                if (!"thinking".equals(block.optString("type"))) continue;
                String t = block.optString("thinking", "");
                if (t.isEmpty()) t = block.optString("text", "");
                if (!t.isEmpty()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(t);
                }
            }
            String out = sb.toString().trim();
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    public static List<LLMToolCall> parseAnthropicToolCalls(JSONObject root) {
        List<LLMToolCall> result = new ArrayList<>();
        try {
            JSONArray content = root.getJSONArray("content");
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.getJSONObject(i);
                if (!"tool_use".equals(block.optString("type"))) continue;
                String name = block.getString("name");
                JSONObject input = block.optJSONObject("input");
                Map<String, Object> args = (input != null) ? jsonObjectToMap(input) : new LinkedHashMap<String, Object>();
                result.add(new LLMToolCall(name, args));
            }
        } catch (Exception e) {
            log.warn("Starlogue: failed to parse Anthropic tool_use blocks: " + e.getMessage());
        }
        return result;
    }

    private static Map<String, Object> jsonObjectToMap(JSONObject obj) {
        Map<String, Object> map = new LinkedHashMap<>();
        java.util.Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                Object val = obj.get(key);
                if (val instanceof JSONObject) {
                    map.put(key, jsonObjectToMap((JSONObject) val));
                } else {
                    map.put(key, val);
                }
            } catch (org.json.JSONException e) {
                // skip malformed entries
            }
        }
        return map;
    }
}
