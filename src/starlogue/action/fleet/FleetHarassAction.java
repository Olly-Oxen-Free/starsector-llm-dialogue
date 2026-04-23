package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.RepLevel;
import org.apache.log4j.Logger;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;

import java.util.Collections;
import java.util.Map;

/** Low-grade intimidation — shadow the player without committing to full attack. */
public class FleetHarassAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(FleetHarassAction.class);

    @Override public String getId() { return "fleet_harass"; }

    @Override
    public String getDescription() {
        return "Harass / shadow the player fleet at low escalation (short duration).";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Collections.emptyMap();
    }

    @Override public boolean isBluffable() { return true; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (ctx.fleet == null) return false;
        return ctx.repLevel == null || !ctx.repLevel.isAtBest(RepLevel.VENGEFUL);
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (ctx.fleet == null) return;
        CampaignFleetAPI player = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
        if (player == null) return;
        ctx.fleet.clearAssignments();
        ctx.fleet.addAssignment(FleetAssignment.ORBIT_AGGRESSIVE, player, 1.5f);
        log.debug("Starlogue: fleet_harass");
    }

    @Override
    public String narrativeNote() {
        return "They close uncomfortably — weapons cold, for now.";
    }
}
