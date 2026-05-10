package starlogue.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * HttpHandler that dispatches JSON-RPC 2.0 requests arriving at POST /mcp.
 *
 * Each inbound request is:
 *  1. Verified to originate from 127.0.0.1 (localhost-only policy).
 *  2. Parsed as a JSON-RPC envelope.
 *  3. Dispatched to the appropriate handler method.
 *  4. Responded with a JSON-RPC envelope.
 *
 * {@code tools/list} and {@code tools/call} are delegated to
 * {@link McpToolSchema} and {@link McpToolBridge} respectively.
 */
class McpRpcHandler implements HttpHandler {

    private static final Logger log = Logger.getLogger(McpRpcHandler.class);

    private static final String CONTENT_TYPE         = "application/json";
    private static final int    HTTP_OK              = 200;
    private static final int    HTTP_NO_CONTENT      = 204;
    private static final int    HTTP_FORBIDDEN       = 403;
    private static final int    HTTP_BAD_REQ         = 400;
    private static final int    HTTP_NOT_ALLOWED     = 405;

    private final McpToolBridge bridge;
    private volatile McpToolSchema schema;

    McpRpcHandler(McpToolBridge bridge) {
        this.bridge = bridge;
    }

    void setSchema(McpToolSchema schema) {
        this.schema = schema;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Enforce localhost-only
        InetSocketAddress remote = exchange.getRemoteAddress();
        if (remote == null || !isLocalhost(remote)) {
            exchange.sendResponseHeaders(HTTP_FORBIDDEN, -1);
            exchange.close();
            return;
        }

        // Only accept POST
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(HTTP_NOT_ALLOWED, -1);
            exchange.close();
            return;
        }

        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        JSONObject req = McpJsonRpc.parse(body);
        if (req == null) {
            sendJson(exchange, HTTP_BAD_REQ,
                McpJsonRpc.error(null, McpJsonRpc.ERR_INVALID_PARAMS, "Could not parse JSON-RPC body"));
            return;
        }

        String method = McpJsonRpc.method(req);
        Object id     = McpJsonRpc.id(req);

        if (method == null) {
            sendJson(exchange, HTTP_BAD_REQ,
                McpJsonRpc.error(id, McpJsonRpc.ERR_INVALID_PARAMS, "Missing 'method' field"));
            return;
        }

        // notifications/initialized has no JSON-RPC response body
        if ("notifications/initialized".equals(method)) {
            exchange.sendResponseHeaders(HTTP_NO_CONTENT, -1);
            exchange.close();
            return;
        }

        JSONObject result;
        try {
            result = dispatch(method, McpJsonRpc.params(req));
        } catch (MethodNotFoundException e) {
            sendJson(exchange, HTTP_OK,
                McpJsonRpc.error(id, McpJsonRpc.ERR_METHOD_NOT_FOUND, "Method not found: " + method));
            return;
        } catch (Exception e) {
            log.error("MCP: unhandled error in method '" + method + "'", e);
            sendJson(exchange, HTTP_OK,
                McpJsonRpc.error(id, McpJsonRpc.ERR_INTERNAL, "Internal error: " + e.getMessage()));
            return;
        }

        sendJson(exchange, HTTP_OK, McpJsonRpc.success(id, result));
    }

    private JSONObject dispatch(String method, JSONObject params) throws Exception {
        return switch (method) {
            case "initialize"  -> handleInitialize(params);
            case "ping"        -> handlePing();
            case "tools/list"  -> handleToolsList();
            case "tools/call"  -> handleToolsCall(params);
            default            -> throw new MethodNotFoundException(method);
        };
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private JSONObject handleInitialize(JSONObject params) throws JSONException {
        JSONObject caps = new JSONObject();
        JSONObject toolsCap = new JSONObject();
        toolsCap.put("listChanged", false);
        caps.put("tools", toolsCap);

        JSONObject serverInfo = new JSONObject();
        serverInfo.put("name", "starlogue");
        serverInfo.put("version", "0.1.0");

        JSONObject result = new JSONObject();
        result.put("protocolVersion", "2024-11-05");
        result.put("capabilities", caps);
        result.put("serverInfo", serverInfo);
        return result;
    }

    private JSONObject handlePing() {
        return new JSONObject();
    }

    private JSONObject handleToolsList() throws JSONException {
        JSONArray tools = (schema != null) ? schema.toMcpToolsArray() : new JSONArray();
        JSONObject result = new JSONObject();
        result.put("tools", tools);
        return result;
    }

    private JSONObject handleToolsCall(JSONObject params) throws JSONException {
        String toolName = params.optString("name", null);
        JSONObject args = params.optJSONObject("arguments");
        if (args == null) args = new JSONObject();

        if (toolName == null) {
            JSONObject err = new JSONObject();
            err.put("content", new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", "[error] Missing tool name")));
            err.put("isError", true);
            return err;
        }

        if (bridge == null) {
            JSONObject err = new JSONObject();
            err.put("content", new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", "[error] Bridge not available")));
            err.put("isError", true);
            return err;
        }

        return bridge.invokeSync(toolName, args);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private boolean isLocalhost(InetSocketAddress addr) {
        var ia = addr.getAddress();
        return ia != null && ia.isLoopbackAddress();
    }

    private void sendJson(HttpExchange exchange, int status, JSONObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class MethodNotFoundException extends Exception {
        MethodNotFoundException(String method) { super(method); }
    }
}
