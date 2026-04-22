package starlogue.starlords.action;

import com.fs.starfarer.api.campaign.FactionAPI;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import starlords.person.Lord;
import starlords.util.DefectionUtils;
import org.apache.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;

public class PerformDefectionAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(PerformDefectionAction.class);

    @Override public String getId() { return "perform_defection"; }

    @Override
    public String getDescription() {
        return "The lord defects to their preferred faction immediately. "
             + "Use only when negotiation has concluded in this conversation and the player has made a compelling offer. "
             + "This is irreversible — describe the lord's decision before invoking. "
             + "offer_summary: one sentence on what the player offered that sealed the deal.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("offer_summary", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (!(ctx.lordData instanceof Lord)) return false;
        Lord lord = (Lord) ctx.lordData;
        if (DefectionUtils.getAutoBetrayalChance(lord) <= 0) return false;
        FactionAPI preferred = DefectionUtils.getLordPreferredFaction(lord, false);
        if (preferred == null || preferred.equals(lord.getFaction())) return false;
        return DefectionUtils.getFactionDefectionEligibility(lord, preferred);
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (!(ctx.lordData instanceof Lord)) return;
        Lord lord = (Lord) ctx.lordData;
        FactionAPI preferred = DefectionUtils.getLordPreferredFaction(lord, false);
        if (preferred == null || preferred.equals(lord.getFaction())) {
            log.warn("Starlogue perform_defection: no valid preferred faction at execution time");
            return;
        }
        DefectionUtils.performDefection(lord, preferred, true);
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.SIGNED_AGREEMENT, 3.0f);
        log.info("Starlogue: " + ctx.person.getNameString()
            + " defected to " + preferred.getDisplayName());
    }

    @Override
    public String narrativeNote() { return "The lord's fleet turns. They have crossed over."; }
}
