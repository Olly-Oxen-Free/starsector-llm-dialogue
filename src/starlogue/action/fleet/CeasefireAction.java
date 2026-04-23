package starlogue.action.fleet;

import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.RepLevel;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import org.apache.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The NPC fleet agrees to a temporary ceasefire.
 * Calls setNoEngaging for the agreed duration and records a memory event.
 * Does not alter faction standing — purely a per-encounter truce.
 */
public class CeasefireAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(CeasefireAction.class);

    private static final float MAX_DURATION = 90f;

    @Override public String getId() { return "ceasefire"; }

    @Override
    public String getDescription() {
        return "The NPC fleet agrees to a temporary ceasefire — no combat for a set number of days. "
             + "Does not change faction standing. Use when tensions are high but both sides "
             + "want to step back from immediate conflict. duration_days max 90.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("duration_days", "number");
        return p;
    }

    @Override
    public java.util.Map<String, String> getParameterDescriptions() {
        java.util.Map<String, String> d = new java.util.LinkedHashMap<>();
        d.put("duration_days", "Ceasefire length in in-game days. Range: 1-90.");
        return d;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        // Only when situation is tense enough to need a ceasefire
        // (not when already friendly, not when totally VENGEFUL)
        if (ctx.repLevel == null) return false;
        return ctx.repLevel.isAtBest(RepLevel.NEUTRAL)
                && !ctx.repLevel.isAtBest(RepLevel.VENGEFUL);
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        float days = 30f;
        Object daysObj = args.get("duration_days");
        if (daysObj instanceof Number) days = ((Number) daysObj).floatValue();
        days = Math.min(days, MAX_DURATION);

        if (ctx.fleet != null) {
            ctx.fleet.setNoEngaging(days);
        }
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.DEESCALATED, 1.0f);
        log.debug("Starlogue: ceasefire " + (int) days + " days with "
            + (ctx.person != null ? ctx.person.getNameString() : "unknown"));
    }

    @Override
    public String narrativeNote() { return "A temporary ceasefire is in effect."; }
}
