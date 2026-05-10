package starlogue.mcp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Utility methods for building JSON-RPC 2.0 request/response/error envelopes.
 * All methods operate on the org.json types bundled in Starsector's json.jar.
 *
 * Note: the bundled json.jar predates the modern org.json API; {@code put()}
 * throws checked {@link JSONException} and {@code keySet()} is absent.
 * All public methods in this class absorb those exceptions internally.
 */
public final class McpJsonRpc {

    private McpJsonRpc() {}

    /** Parse a raw JSON string into a JSONObject. Returns null on parse failure. */
    public static JSONObject parse(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** Extract the method string from a parsed request. Returns null if absent. */
    public static String method(JSONObject req) {
        if (req == null || !req.has("method")) return null;
        return req.optString("method", null);
    }

    /** Extract params object; returns empty JSONObject if absent. */
    public static JSONObject params(JSONObject req) {
        if (req == null || !req.has("params")) return new JSONObject();
        Object p = req.opt("params");
        if (p instanceof JSONObject) return (JSONObject) p;
        return new JSONObject();
    }

    /** Extract the request id (may be String, Integer, or null). */
    public static Object id(JSONObject req) {
        if (req == null || !req.has("id")) return null;
        return req.opt("id");
    }

    /**
     * Build a success response envelope.
     * Never throws; JSONException is caught and logged internally.
     */
    public static JSONObject success(Object id, Object result) {
        JSONObject resp = new JSONObject();
        try {
            resp.put("jsonrpc", "2.0");
            if (id != null) resp.put("id", id);
            if (result instanceof JSONObject)    resp.put("result", (JSONObject) result);
            else if (result instanceof JSONArray) resp.put("result", (JSONArray) result);
            else if (result != null)             resp.put("result", result);
        } catch (JSONException e) {
            // Should never happen for these well-typed calls
            throw new RuntimeException("McpJsonRpc.success: unexpected JSONException", e);
        }
        return resp;
    }

    /**
     * Build an error response envelope.
     * Never throws.
     */
    public static JSONObject error(Object id, int code, String message) {
        JSONObject resp = new JSONObject();
        try {
            resp.put("jsonrpc", "2.0");
            if (id != null) resp.put("id", id);
            JSONObject err = new JSONObject();
            err.put("code", code);
            err.put("message", message != null ? message : "");
            resp.put("error", err);
        } catch (JSONException e) {
            throw new RuntimeException("McpJsonRpc.error: unexpected JSONException", e);
        }
        return resp;
    }

    // Standard JSON-RPC error codes
    /** Method not found. */
    public static final int ERR_METHOD_NOT_FOUND = -32601;
    /** Invalid params. */
    public static final int ERR_INVALID_PARAMS   = -32602;
    /** Internal error. */
    public static final int ERR_INTERNAL         = -32603;
    /** Application-level tool execution error. */
    public static final int ERR_TOOL_EXEC        = -32000;
}
