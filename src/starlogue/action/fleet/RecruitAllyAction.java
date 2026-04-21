package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
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
 * The NPC fleet agrees to temporarily escort the player.
 * Sets a FOLLOW assignment toward the player fleet for a limited time.
 */
public class RecruitAllyAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(RecruitAllyAction.class);

    @Override public String getId() { return "recruit_ally"; }

    @Override
    public String getDescription() {
        return "The NPC fleet agrees to escort or fight alongside the player temporarily. "
             + "Use after the player has successfully convinced the NPC to help. "
             + "duration_days is how many in-game days the escort lasts (max 30).";
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
        // Requires meaningful positive history
        return ctx.memoryScore > 20f && !ctx.repLevel.isAtBest(RepLevel.NEUTRAL);
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (ctx.fleet == null) return;

        float days = 7f;
        Object daysObj = args.get("duration_days");
        if (daysObj instanceof Number) days = ((Number) daysObj).floatValue();
        days = Math.min(days, 30f);

        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
        if (playerFleet == null) return;

        ctx.fleet.clearAssignments();
        ctx.fleet.addAssignment(FleetAssignment.FOLLOW, playerFleet, days);

        MemoryEngine.recordEvent(ctx.person, MemoryEvent.HELPED_IN_BATTLE, 1.0f);
        log.debug("Starlogue: recruit_ally " + (int) days + " days for " + ctx.person.getNameString());
    }

    @Override
    public String narrativeNote() { return "They fall into formation alongside your fleet."; }
}
