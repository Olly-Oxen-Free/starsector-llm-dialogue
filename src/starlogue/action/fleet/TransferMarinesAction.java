package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import org.apache.log4j.Logger;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;

import java.util.LinkedHashMap;
import java.util.Map;

public class TransferMarinesAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(TransferMarinesAction.class);
    private static final float MAX = 500f;

    @Override public String getId() { return "transfer_marines"; }

    @Override
    public String getDescription() {
        return "Transfer marines: quantity (number), direction npc_to_player or player_to_npc.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("quantity", "number");
        p.put("direction", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (ctx.fleet == null || Global.getSector().getPlayerFleet() == null) return false;
        return !ctx.repLevel.isAtBest(RepLevel.VENGEFUL);
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (ctx.fleet == null || Global.getSector().getPlayerFleet() == null) return;
        float qty = asFloat(args.get("quantity"));
        if (qty <= 0f) return;
        float amount = Math.min(qty, MAX);
        boolean npcToPlayer = !"player_to_npc".equalsIgnoreCase(String.valueOf(args.get("direction")));
        CargoAPI npc = ctx.fleet.getCargo();
        CargoAPI player = Global.getSector().getPlayerFleet().getCargo();
        if (npcToPlayer) {
            float actual = Math.min(amount, npc.getCommodityQuantity(Commodities.MARINES));
            if (actual <= 0f) return;
            npc.removeCommodity(Commodities.MARINES, actual);
            player.addCommodity(Commodities.MARINES, actual);
        } else {
            float actual = Math.min(amount, player.getCommodityQuantity(Commodities.MARINES));
            if (actual <= 0f) return;
            player.removeCommodity(Commodities.MARINES, actual);
            npc.addCommodity(Commodities.MARINES, actual);
        }
        log.debug("Starlogue: transfer_marines");
    }

    @Override
    public String narrativeNote() {
        return "Marines transferred.";
    }

    private static float asFloat(Object val) {
        if (val instanceof Number) return ((Number) val).floatValue();
        try {
            return Float.parseFloat(String.valueOf(val));
        } catch (Throwable t) {
            return 0f;
        }
    }
}
