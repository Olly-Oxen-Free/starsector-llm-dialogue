package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import org.apache.log4j.Logger;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Break off pursuit without a full retreat-to-despawn commitment. */
public class FleetDisengageSoftAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(FleetDisengageSoftAction.class);

    @Override public String getId() { return "fleet_disengage_soft"; }

    @Override
    public String getDescription() {
        return "Stand down aggressive pursuit: optional reason (string). Softer than retreat.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("reason", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        return ctx.fleet != null;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (ctx.fleet == null) return;
        CampaignFleetAPI player = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
        ctx.fleet.clearAssignments();
        if (player != null) {
            ctx.fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, player, 2f);
        } else {
            ctx.fleet.addAssignment(FleetAssignment.STANDING_DOWN, null, 1f);
        }
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.DEESCALATED, 1.0f);
        log.debug("Starlogue: fleet_disengage_soft");
    }

    @Override
    public String narrativeNote() {
        return "They throttle back and break off.";
    }
}
