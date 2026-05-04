package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.RepLevel;
import org.apache.log4j.Logger;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;

import java.util.LinkedHashMap;
import java.util.Map;

public class FleetEscortPlayerAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(FleetEscortPlayerAction.class);

    @Override public String getId() { return "fleet_escort_player"; }

    @Override
    public String getDescription() {
        return "Follow / escort the player fleet for duration_days (number, max 30). Requires cooperative stance.";
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
        if (ctx.fleet == null) return false;
        if (ctx.repLevel.isAtBest(RepLevel.HOSTILE)) return false;
        return ctx.memoryScore > 5f;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (ctx.fleet == null) return;
        CampaignFleetAPI player = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
        if (player == null) return;
        float days = 14f;
        Object d = args.get("duration_days");
        if (d instanceof Number) days = Math.min(30f, Math.max(1f, ((Number) d).floatValue()));
        ctx.fleet.clearAssignments();
        ctx.fleet.addAssignment(FleetAssignment.FOLLOW, player, days);
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.SIGNED_AGREEMENT, 0.5f);
        log.info("Starlogue: fleet_escort_player " + (int) days + "d");
    }

    @Override
    public String narrativeNote() {
        return "Their fleet forms up on your wing.";
    }
}
