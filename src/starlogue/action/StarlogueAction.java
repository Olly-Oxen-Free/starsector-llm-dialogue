package starlogue.action;

import starlogue.engine.GameContext;
import java.util.Map;

public interface StarlogueAction {
    String getId();
    String getDescription();
    /** Parameter schema: name → JSON type string ("string", "number", "boolean"). */
    Map<String, Object> getParameters();
    boolean isAvailable(GameContext ctx);
    /** True if a reckless/aggressive NPC may threaten this action even when unavailable. */
    boolean isBluffable();
    void execute(GameContext ctx, Map<String, Object> args);
    /** Short narrative string shown in the UI after execution. Null = silent. */
    String narrativeNote();
}
