package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import org.apache.log4j.Logger;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;

import java.util.LinkedHashMap;
import java.util.Map;

public class TransferSuppliesAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(TransferSuppliesAction.class);
    private static final float MAX_TRANSFER = 2000f;

    @Override
    public String getId() {
        return "transfer_supplies";
    }

    @Override
    public String getDescription() {
        return "Transfer supplies between fleets after terms are agreed. "
            + "quantity is units to move, direction is npc_to_player or player_to_npc.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("quantity", "number");
        p.put("direction", "string");
        return p;
    }

    @Override
    public java.util.Map<String, String> getParameterDescriptions() {
        java.util.Map<String, String> d = new java.util.LinkedHashMap<>();
        d.put("quantity", "Units of supplies to transfer. Positive number.");
        d.put("direction", "Transfer direction: 'npc_to_player' or 'player_to_npc'.");
        return d;
    }

    @Override
    public boolean isAvailable(GameContext ctx) {
        return ctx.fleet != null && Global.getSector().getPlayerFleet() != null;
    }

    @Override
    public boolean isBluffable() {
        return false;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (ctx.fleet == null || Global.getSector().getPlayerFleet() == null) return;
        float qty = asFloat(args.get("quantity"));
        if (qty <= 0f) return;
        float amount = Math.min(qty, MAX_TRANSFER);
        boolean npcToPlayer = !"player_to_npc".equalsIgnoreCase(String.valueOf(args.get("direction")));

        CargoAPI npc = ctx.fleet.getCargo();
        CargoAPI player = Global.getSector().getPlayerFleet().getCargo();
        if (npcToPlayer) {
            float actual = Math.min(amount, npc.getCommodityQuantity(Commodities.SUPPLIES));
            if (actual <= 0f) return;
            npc.removeCommodity(Commodities.SUPPLIES, actual);
            player.addCommodity(Commodities.SUPPLIES, actual);
            log.debug("Starlogue: transfer_supplies npc_to_player=" + (int) actual);
        } else {
            float actual = Math.min(amount, player.getCommodityQuantity(Commodities.SUPPLIES));
            if (actual <= 0f) return;
            player.removeCommodity(Commodities.SUPPLIES, actual);
            npc.addCommodity(Commodities.SUPPLIES, actual);
            log.debug("Starlogue: transfer_supplies player_to_npc=" + (int) actual);
        }
    }

    @Override
    public String narrativeNote() {
        return "Supplies transferred.";
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
