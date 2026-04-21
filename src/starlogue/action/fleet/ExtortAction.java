package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.RepLevel;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import org.apache.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;

public class ExtortAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(ExtortAction.class);

    @Override public String getId() { return "extort"; }

    @Override
    public String getDescription() {
        return "The NPC demands a credit payment in exchange for letting the player pass. "
             + "Specify the amount in credits.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("credits", "number");
        p.put("or_else", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        // NPC needs leverage — equal or stronger fleet
        if (ctx.strengthDelta < -0.10f) return false;
        // Only hostile-or-worse factions extort (not WELCOMING or better)
        if (ctx.repLevel != null && !ctx.repLevel.isAtBest(RepLevel.FAVORABLE)) return false;
        return true;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        double raw = ((Number) args.get("credits")).doubleValue();
        float playerCredits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        // Clamp: at most 10% of player credits, between 1000 and 200000
        float clamped = Math.max(1000f, Math.min((float) raw, Math.min(200000f, playerCredits * 0.10f)));
        Global.getSector().getPlayerFleet().getCargo().getCredits().add(-clamped);

        MemoryEngine.recordEvent(ctx.person, MemoryEvent.EXTORTED, 1.0f);
        log.info("Starlogue: extort — player paid " + clamped + " credits");
    }

    @Override public String narrativeNote() { return "Credits transferred."; }
}
