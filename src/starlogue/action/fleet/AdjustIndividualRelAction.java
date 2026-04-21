package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import lunalib.lunaSettings.LunaSettings;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import org.apache.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;

public class AdjustIndividualRelAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(AdjustIndividualRelAction.class);

    @Override public String getId() { return "adjust_individual_rel"; }

    @Override
    public String getDescription() {
        return "Adjust this NPC faction's opinion of the player. Use for meaningful moments. "
             + "delta: -0.10 to +0.10 (each 0.05 ≈ one rep tier step). "
             + "Positive delta improves relations, negative worsens them.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("delta", "number");
        p.put("reason", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        // Not available at the extremes (can't improve cooperative, can't worsen vengeful)
        if (ctx.repLevel == null) return false;
        // Not available if already cooperative (can't go higher) or vengeful (can't go lower)
        if (!ctx.repLevel.isAtBest(RepLevel.FRIENDLY)) return false;  // already COOPERATIVE
        if (ctx.repLevel.isAtBest(RepLevel.VENGEFUL)) return false;
        return true;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        double raw = ((Number) args.get("delta")).doubleValue();
        float delta = (float) Math.max(-0.10, Math.min(0.10, raw));

        boolean isPositive = delta > 0;
        String capKey = isPositive ? "$starlogue_rep_gained_30d" : "$starlogue_rep_lost_30d";

        MemoryAPI playerMem = Global.getSector().getPlayerFleet().getMemory();
        float used = playerMem.contains(capKey) ? playerMem.getFloat(capKey) : 0f;

        Float capFloat = isPositive
            ? LunaSettings.getFloat("starlogue", "starlogue_rep_gain_cap")
            : LunaSettings.getFloat("starlogue", "starlogue_rep_loss_cap");
        float cap = capFloat != null ? capFloat : 20f;
        float allowed = cap - Math.abs(used);
        if (allowed <= 0f) {
            log.debug("Starlogue: monthly rep cap hit for " + (isPositive ? "gain" : "loss"));
            return;
        }

        float effective = isPositive
            ? Math.min(delta, allowed)
            : Math.max(delta, -allowed);

        ctx.npcFaction.adjustRelationship(ctx.playerFaction.getId(), effective);
        playerMem.set(capKey, used + Math.abs(effective), 30f);

        MemoryEvent evt = delta > 0 ? MemoryEvent.HELPED_IN_BATTLE : MemoryEvent.OFFENDED;
        MemoryEngine.recordEvent(ctx.person, evt, 1.0f);
        log.debug("Starlogue: adjust_individual_rel effective=" + effective);
    }

    @Override public String narrativeNote() { return null; }
}
