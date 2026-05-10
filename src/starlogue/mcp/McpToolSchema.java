package starlogue.mcp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import starlogue.action.StarlogueAction;
import starlogue.engine.EvaluatedActionSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts an {@link EvaluatedActionSet} into MCP tool descriptors
 * ({@code {name, description, inputSchema}}) for a {@code tools/list} response.
 *
 * Tool names are prefixed with {@code starlogue_} to avoid collisions with
 * Claude Code built-ins (e.g. {@code starlogue_attack}, {@code starlogue_share_intel}).
 *
 * Bluff-only actions are excluded — only {@code EvaluatedActionSet.available} is used,
 * matching the existing OpenAI tools-array behaviour in
 * {@link starlogue.engine.ConstraintEngine#buildToolsArray}.
 */
public class McpToolSchema {

    private static final String PREFIX = "starlogue_";

    /** The reverse map: MCP tool name → StarlogueAction. */
    private final Map<String, StarlogueAction> lookup;
    /** Ordered list of MCP tool JSON descriptors. */
    private final List<JSONObject> mcpTools;

    public McpToolSchema(EvaluatedActionSet set) {
        Map<String, StarlogueAction> lk = new LinkedHashMap<>();
        List<JSONObject> tools = new ArrayList<>();

        for (StarlogueAction action : set.available) {
            String mcpName = PREFIX + action.getId();
            lk.put(mcpName, action);
            try {
                tools.add(buildToolDescriptor(mcpName, action));
            } catch (JSONException e) {
                throw new RuntimeException("McpToolSchema: failed to build descriptor for " + mcpName, e);
            }
        }

        this.lookup   = Collections.unmodifiableMap(lk);
        this.mcpTools = Collections.unmodifiableList(tools);
    }

    /**
     * Returns the list of MCP tool descriptors for use in {@code tools/list}.
     */
    public List<JSONObject> toMcpTools() {
        return mcpTools;
    }

    /**
     * Returns a JSONArray of tool descriptors ready to embed in the
     * {@code tools/list} result envelope.
     */
    public JSONArray toMcpToolsArray() {
        JSONArray arr = new JSONArray();
        for (JSONObject t : mcpTools) arr.put(t);
        return arr;
    }

    /**
     * Look up the original {@link StarlogueAction} by its prefixed MCP tool name.
     *
     * @param mcpToolName e.g. {@code "starlogue_attack"}
     * @return the action, or {@code null} if not found
     */
    public StarlogueAction lookup(String mcpToolName) {
        return lookup.get(mcpToolName);
    }

    // ── Schema building ───────────────────────────────────────────────────────

    private static JSONObject buildToolDescriptor(String mcpName, StarlogueAction action)
            throws JSONException {
        JSONObject tool = new JSONObject();
        tool.put("name", mcpName);
        tool.put("description", action.getDescription());
        tool.put("inputSchema", buildInputSchema(action));
        return tool;
    }

    /**
     * Builds a JSON Schema draft-07 {@code inputSchema} from the action's parameter map.
     * Replicates the logic in {@code ConstraintEngine.buildParamSchema} but emits
     * a {@link JSONObject} rather than a {@code Map<String,Object>}.
     */
    private static JSONObject buildInputSchema(StarlogueAction action) throws JSONException {
        Map<String, Object> paramDefs    = action.getParameters();
        Map<String, String> descriptions = action.getParameterDescriptions();

        JSONObject schema     = new JSONObject();
        JSONObject properties = new JSONObject();
        JSONArray  required   = new JSONArray();

        schema.put("type", "object");

        for (Map.Entry<String, Object> e : paramDefs.entrySet()) {
            JSONObject typeObj = new JSONObject();
            typeObj.put("type", e.getValue());   // "string", "number", "boolean"
            String desc = descriptions.get(e.getKey());
            if (desc != null && !desc.isBlank()) {
                typeObj.put("description", desc);
            }
            properties.put(e.getKey(), typeObj);
            required.put(e.getKey());
        }

        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }
}
