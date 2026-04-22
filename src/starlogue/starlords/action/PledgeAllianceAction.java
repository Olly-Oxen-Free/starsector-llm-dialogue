package starlogue.starlords.action;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import starlords.person.Lord;
import org.apache.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Star Lord pledges to fight alongside the player.
 * Sets playerDirected=true on the lord and assigns FOLLOW to player fleet.
 * Long-memory event (3x multiplier) — this relationship should persist.
 *
 * Gate: very high memory score (deep positive history), not already pledged.
 */
public class PledgeAllianceAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(PledgeAllianceAction.class);

    @Override public String getId() { return "pledge_alliance"; }

    @Override
    public String getDescription() {
        return "The lord pledges to fight alongside you as an ally, following your fleet and "
             + "supporting you in battle. Use only after the player has earned deep trust. "
             + "duration_days: how long the pledge lasts (max 60). This is a major commitment.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("duration_days", "number");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (!(ctx.lordData instanceof Lord)) return false;
        Lord lord = (Lord) ctx.lordData;
        // Requires deep positive history and not already pledged
        return ctx.memoryScore > 50f && !lord.isPlayerDirected();
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (!(ctx.lordData instanceof Lord)) return;
        Lord lord = (Lord) ctx.lordData;

        float days = 30f;
        Object daysObj = args.get("duration_days");
        if (daysObj instanceof Number) days = ((Number) daysObj).floatValue();
        days = Math.min(days, 60f);

        lord.setPlayerDirected(true);

        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
        if (ctx.fleet != null && playerFleet != null) {
            ctx.fleet.clearAssignments();
            ctx.fleet.addAssignment(FleetAssignment.FOLLOW, playerFleet, days);
        }

        // 3x multiplier — lord remembers this for a long time
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.SIGNED_AGREEMENT, 3.0f);
        log.info("Starlogue: " + ctx.person.getNameString() + " pledged alliance for " + (int) days + " days");
    }

    @Override
    public String narrativeNote() { return "The lord's fleet falls into formation with yours. You have an ally."; }
}
