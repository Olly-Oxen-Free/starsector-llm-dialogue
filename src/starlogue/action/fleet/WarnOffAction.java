package starlogue.action.fleet;

import com.fs.starfarer.api.campaign.RepLevel;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import org.apache.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The NPC fleet warns the player to leave but holds fire for now.
 * Sets setNoEngaging for a window, giving the player time to withdraw.
 */
public class WarnOffAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(WarnOffAction.class);

    @Override public String getId() { return "warn_off"; }

    @Override
    public String getDescription() {
        return "The NPC fleet issues a formal warning and holds fire for a short window. "
             + "Use when the NPC wants the player to leave without triggering immediate combat.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("warning_message", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        // Always available — NPCs can always choose to warn rather than shoot
        return true;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (ctx.fleet != null) {
            // Give the player ~2 in-game days before the fleet will engage again
            ctx.fleet.setNoEngaging(2f);
        }
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.OFFENDED, 1.0f);
        log.debug("Starlogue: warn_off executed by " + (ctx.person != null ? ctx.person.getNameString() : "unknown"));
    }

    @Override
    public String narrativeNote() {
        return "They hold fire — for now. You have a window to leave.";
    }
}
