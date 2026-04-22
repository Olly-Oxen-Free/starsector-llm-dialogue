package starlogue.starlords.action;

import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import starlords.person.Lord;
import org.apache.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The player convinces the lord to adopt a political position.
 * Sets lord.isSwayed() = true, influencing council votes and lord AI decisions.
 * One-time effect — a lord can only be swayed once.
 */
public class SwayLordAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(SwayLordAction.class);

    @Override public String getId() { return "sway_lord"; }

    @Override
    public String getDescription() {
        return "The lord agrees to support the player's political position — "
             + "this affects council votes and lord alliances. "
             + "Only possible after significant trust has been built. One-time effect. "
             + "position: briefly describe what political stance the lord is being swayed toward.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("position", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (!(ctx.lordData instanceof Lord)) return false;
        Lord lord = (Lord) ctx.lordData;
        return ctx.memoryScore > 30f && !lord.isSwayed();
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (!(ctx.lordData instanceof Lord)) return;
        Lord lord = (Lord) ctx.lordData;
        lord.setSwayed(true);
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.POLITICALLY_ALLIED, 3.0f);
        log.info("Starlogue: " + ctx.person.getNameString() + " swayed to support player");
    }

    @Override
    public String narrativeNote() { return "The lord nods. Their political support is secured."; }
}
