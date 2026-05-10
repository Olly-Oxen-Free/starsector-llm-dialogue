package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import starlogue.action.StarlogueAction;
import starlogue.config.LunaSettingHelper;
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

    @Override
    public java.util.Map<String, String> getParameterDescriptions() {
        java.util.Map<String, String> d = new java.util.LinkedHashMap<>();
        d.put("delta", "Individual relationship change. Range: -0.10 to +0.10. Each 0.05 is approximately one reputation tier step.");
        d.put("reason", "Brief narrative reason for the shift.");
        return d;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        return ctx.repLevel != null && ctx.npcFaction != null && ctx.playerFaction != null;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        Object rawObj = args.get("delta");
        if (rawObj == null) return;
        double raw = asFloat(rawObj);
        float delta = (float) Math.max(-0.10, Math.min(0.10, raw));

        boolean isPositive = delta > 0;
        String capKey = isPositive ? "$starlogue_rep_gained_30d" : "$starlogue_rep_lost_30d";

        MemoryAPI playerMem = Global.getSector().getPlayerFleet().getMemory();
        float used = playerMem.contains(capKey) ? playerMem.getFloat(capKey) : 0f;

        float cap = (float) (isPositive
            ? LunaSettingHelper.getDouble("starlogue_rep_gain_cap", 20.0)
            : LunaSettingHelper.getDouble("starlogue_rep_loss_cap", 20.0));
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

    /** Coerces numeric or string-typed JSON values to float. LLMs sometimes return numbers as strings. */
    private static float asFloat(Object val) {
        if (val instanceof Number) return ((Number) val).floatValue();
        try {
            return Float.parseFloat(String.valueOf(val));
        } catch (Throwable t) {
            return 0f;
        }
    }
}
