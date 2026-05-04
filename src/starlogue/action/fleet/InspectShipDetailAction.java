package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import org.apache.log4j.Logger;
import starlogue.action.StarlogueAction;
import starlogue.engine.FleetSnapshotFormatter;
import starlogue.engine.GameContext;

import java.util.LinkedHashMap;
import java.util.Map;

/** Deep readout for one hull (d-mods, hullmods) after the player agrees to scrutiny. */
public class InspectShipDetailAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(InspectShipDetailAction.class);
    private String lastNote;

    @Override public String getId() { return "inspect_ship_detail"; }

    @Override
    public String getDescription() {
        return "Detailed inspection of a single ship in player or NPC fleet. "
            + "which_fleet: player | npc. selector: hull id preferred, else substring of ship name.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("which_fleet", "string");
        p.put("selector", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        CampaignFleetAPI pf = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
        return pf != null;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        String side = String.valueOf(args.getOrDefault("which_fleet", "player")).trim().toLowerCase();
        String sel = String.valueOf(args.get("selector"));
        CampaignFleetAPI fleet;
        if ("npc".equals(side)) {
            fleet = ctx.fleet;
        } else {
            fleet = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
        }
        lastNote = FleetSnapshotFormatter.formatShipDetail(fleet, sel);
        log.debug("Starlogue: inspect_ship_detail side=" + side);
    }

    @Override
    public String narrativeNote() {
        return lastNote != null ? lastNote : "Ship detail.";
    }
}
