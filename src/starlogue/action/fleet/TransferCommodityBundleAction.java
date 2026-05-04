package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import org.apache.log4j.Logger;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transfer a small negotiated basket. items_json format: {@code id:qty;id2:qty2}
 * (e.g. {@code supplies:50;fuel:100}).
 */
public class TransferCommodityBundleAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(TransferCommodityBundleAction.class);
    private String lastNote;

    @Override public String getId() { return "transfer_commodity_bundle"; }

    @Override
    public String getDescription() {
        return "Transfer multiple commodities in one step. items_json string format id:qty separated by semicolons. "
            + "direction npc_to_player or player_to_npc. Caps per stack apply.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("items_json", "string");
        p.put("direction", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (ctx.fleet == null || Global.getSector().getPlayerFleet() == null) return false;
        return !ctx.repLevel.isAtBest(RepLevel.HOSTILE);
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (ctx.fleet == null || Global.getSector().getPlayerFleet() == null) return;
        String raw = String.valueOf(args.getOrDefault("items_json", ""));
        boolean npcToPlayer = !"player_to_npc".equalsIgnoreCase(String.valueOf(args.get("direction")));
        CargoAPI npc = ctx.fleet.getCargo();
        CargoAPI player = Global.getSector().getPlayerFleet().getCargo();
        StringBuilder done = new StringBuilder();
        for (String part : raw.split(";")) {
            if (part == null || part.trim().isEmpty()) continue;
            String[] kv = part.split(":");
            if (kv.length < 2) continue;
            String id = kv[0].trim();
            float q = asFloat(kv[1].trim());
            if (q <= 0f || id.isEmpty()) continue;
            q = Math.min(q, 2000f);
            if (npcToPlayer) {
                float a = Math.min(q, npc.getCommodityQuantity(id));
                if (a <= 0f) continue;
                npc.removeCommodity(id, a);
                player.addCommodity(id, a);
                done.append(id).append("×").append((int) a).append(" ");
            } else {
                float a = Math.min(q, player.getCommodityQuantity(id));
                if (a <= 0f) continue;
                player.removeCommodity(id, a);
                npc.addCommodity(id, a);
                done.append(id).append("×").append((int) a).append(" ");
            }
        }
        lastNote = done.length() > 0 ? "Transferred: " + done.toString().trim() : "No commodities moved.";
        log.debug("Starlogue: transfer_commodity_bundle");
    }

    @Override
    public String narrativeNote() {
        return lastNote;
    }

    private static float asFloat(String s) {
        try {
            return Float.parseFloat(s);
        } catch (Throwable t) {
            return 0f;
        }
    }
}
