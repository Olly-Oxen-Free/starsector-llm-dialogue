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

/**
 * Adjusts the NPC's entire faction relationship with the player.
 * Higher impact than individual_rel — represents a formal faction position shift.
 * Shares the same monthly cap as adjust_individual_rel.
 * Only available to commanders of Admiral rank or higher (rank check is narrative).
 */
public class AdjustFactionRelAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(AdjustFactionRelAction.class);

    @Override public String getId() { return "adjust_faction_rel"; }

    @Override
    public String getDescription() {
        return "Formally adjust your faction's standing with the player on behalf of your leadership. "
             + "This affects all encounters with your faction, not just this one. "
             + "delta: -0.08 to +0.08. Use sparingly — this is a significant political act. "
             + "Only use if you hold sufficient rank to make this call.";
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
        d.put("delta", "Faction relationship change. Range: -0.08 to +0.08. Positive improves standing, negative worsens it. Use sparingly — this is a significant political act.");
        d.put("reason", "Brief narrative reason for the shift (e.g. 'player defused a border dispute').");
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
        double raw = ((Number) rawObj).doubleValue();
        // Faction-level deltas are smaller than individual — max ±0.08
        float delta = (float) Math.max(-0.08, Math.min(0.08, raw));

        boolean isPositive = delta > 0;
        String capKey = isPositive ? "$starlogue_rep_gained_30d" : "$starlogue_rep_lost_30d";

        MemoryAPI playerMem = Global.getSector().getPlayerFleet().getMemory();
        float used = playerMem.contains(capKey) ? playerMem.getFloat(capKey) : 0f;

        float cap = (float) (isPositive
            ? LunaSettingHelper.getDouble("starlogue_rep_gain_cap", 20.0)
            : LunaSettingHelper.getDouble("starlogue_rep_loss_cap", 20.0));

        float allowed = cap - Math.abs(used);
        if (allowed <= 0f) {
            log.debug("Starlogue: faction rep monthly cap hit for " + (isPositive ? "gain" : "loss"));
            return;
        }

        float effective = isPositive
            ? Math.min(delta, allowed)
            : Math.max(delta, -allowed);

        ctx.npcFaction.adjustRelationship(ctx.playerFaction.getId(), effective);
        playerMem.set(capKey, used + Math.abs(effective), 30f);

        MemoryEvent evt = isPositive ? MemoryEvent.POLITICALLY_ALLIED : MemoryEvent.OFFENDED;
        MemoryEngine.recordEvent(ctx.person, evt, 1.0f);
        log.debug("Starlogue: adjust_faction_rel effective=" + effective + " for faction " + ctx.npcFaction.getId());
    }

    @Override public String narrativeNote() { return null; }
}
