package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import org.apache.log4j.Logger;
import starlogue.action.StarlogueAction;
import starlogue.engine.FleetSnapshotFormatter;
import starlogue.engine.GameContext;

import java.util.LinkedHashMap;
import java.util.Map;

/** On-demand fleet line-of-sight refresh (no d-mods in brief/full). */
public class InspectFleetAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(InspectFleetAction.class);
    private String lastNote;

    @Override public String getId() { return "inspect_fleet"; }

    @Override
    public String getDescription() {
        return "Produce a fresh VISUAL line-of-sight fleet summary. "
            + "which: player | npc | both. detail_level: brief (default cap) or full (more hull lines). "
            + "Use when the player offers proof or asks you to verify their fleet.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("which", "string");
        p.put("detail_level", "string");
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
        CampaignFleetAPI player = Global.getSector() != null ? Global.getSector().getPlayerFleet() : null;
        CampaignFleetAPI npc = ctx.fleet;
        String which = String.valueOf(args.getOrDefault("which", "both")).trim().toLowerCase();
        String detail = String.valueOf(args.getOrDefault("detail_level", "brief")).trim().toLowerCase();
        int cap = "full".equals(detail) ? Math.max(12, FleetSnapshotFormatter.maxShipsDefault()) : FleetSnapshotFormatter.maxShipsDefault();

        StringBuilder sb = new StringBuilder();
        if ("player".equals(which) || "both".equals(which)) {
            sb.append("PLAYER FLEET:\n").append(FleetSnapshotFormatter.formatFleetBrief(player, cap)).append("\n\n");
        }
        if (("npc".equals(which) || "both".equals(which)) && npc != null) {
            sb.append("NPC FLEET:\n").append(FleetSnapshotFormatter.formatFleetBrief(npc, cap)).append("\n\n");
        }
        if (sb.length() == 0) {
            sb.append("(no fleet matched the which= parameter)");
        }
        lastNote = sb.toString().trim();
        log.debug("Starlogue: inspect_fleet which=" + which + " detail=" + detail);
    }

    @Override
    public String narrativeNote() {
        return lastNote != null ? lastNote : "Fleet inspection complete.";
    }
}
