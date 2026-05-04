package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import org.apache.log4j.Logger;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;

import java.util.LinkedHashMap;
import java.util.Map;

public class FleetPatrolHereAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(FleetPatrolHereAction.class);

    @Override public String getId() { return "fleet_patrol_here"; }

    @Override
    public String getDescription() {
        return "Patrol the current system for duration_days (number, max 45). Non-hostile, meaningful trust.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("duration_days", "number");
        p.put("reason", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (ctx.fleet == null) return false;
        if (ctx.repLevel.isAtBest(RepLevel.HOSTILE)) return false;
        return ctx.memoryScore > 15f;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (ctx.fleet == null) return;
        CampaignFleetAPI player = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
        if (player == null) return;
        LocationAPI loc = player.getContainingLocation();
        SectorEntityToken anchor = player;
        try {
            if (loc != null && loc.getPlanets() != null && !loc.getPlanets().isEmpty()) {
                com.fs.starfarer.api.campaign.PlanetAPI p0 = loc.getPlanets().get(0);
                if (p0 != null) {
                    anchor = p0;
                }
            }
        } catch (Throwable ignored) { }
        float days = 21f;
        Object d = args.get("duration_days");
        if (d instanceof Number) days = Math.min(45f, Math.max(3f, ((Number) d).floatValue()));
        ctx.fleet.clearAssignments();
        ctx.fleet.addAssignment(FleetAssignment.PATROL_SYSTEM, anchor, days);
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.POLITICALLY_ALLIED, 0.35f);
        log.info("Starlogue: fleet_patrol_here " + (int) days + "d");
    }

    @Override
    public String narrativeNote() {
        return "They acknowledge patrol orders in this system.";
    }
}
