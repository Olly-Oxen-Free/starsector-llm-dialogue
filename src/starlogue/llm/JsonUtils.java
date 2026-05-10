package starlogue.llm;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.Map;

/**
 * Package-private JSON serialization helpers shared by all LLM client implementations.
 *
 * <p>Extracted from OpenAIClient and AnthropicClient where identical copies existed
 * (KISS-LLM-DUPL). Callers: OpenAIClient, AnthropicClient.
 */
final class JsonUtils {

    private JsonUtils() {}

    static JSONObject mapToJson(Map<String, Object> map) throws org.json.JSONException {
        JSONObject obj = new JSONObject();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            obj.put(e.getKey(), deepToJson(e.getValue()));
        }
        return obj;
    }

    @SuppressWarnings("unchecked")
    static Object deepToJson(Object value) throws org.json.JSONException {
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
