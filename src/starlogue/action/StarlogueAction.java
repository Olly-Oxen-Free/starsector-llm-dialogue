package starlogue.action;

import starlogue.engine.GameContext;
import java.util.Map;

public interface StarlogueAction {
    String getId();
    String getDescription();
    /** Parameter schema: name → JSON type string ("string", "number", "boolean"). */
    Map<String, Object> getParameters();
    /**
     * Optional per-parameter descriptions for the JSON Schema sent to the LLM.
     * Key = parameter name (must match a key returned by {@link #getParameters()}).
     * Value = description string including valid ranges, allowed values, or usage hints.
     * Default: empty map.
     */
    default java.util.Map<String, String> getParameterDescriptions() {
        return java.util.Collections.emptyMap();
    }
    boolean isAvailable(GameContext ctx);
    /** True if a reckless/aggressive NPC may threaten this action even when unavailable. */
    boolean isBluffable();
    void execute(GameContext ctx, Map<String, Object> args);
    /** Short narrative string shown in the UI after execution. Null = silent. */
    String narrativeNote();
}
