package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import org.apache.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The NPC fleet offers specific commodities in exchange for credits.
 * The conversation is the negotiation — the player agreed in dialogue.
 * Commodity IDs: supplies, fuel, crew, metals, ore, heavy_machinery, etc.
 */
public class TradeOfferAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(TradeOfferAction.class);

    private static final float MAX_QTY = 500f;

    @Override public String getId() { return "trade_offer"; }

    @Override
    public String getDescription() {
        return "Execute an agreed trade: transfer commodities from the NPC fleet to the player in exchange for credits. "
             + "Use after the player has verbally accepted the terms. "
             + "commodity_id uses Starsector commodity IDs (supplies, fuel, crew, metals, ore, heavy_machinery). "
             + "price_per_unit is credits per unit of the commodity.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("commodity_id", "string");
        p.put("quantity", "number");
        p.put("price_per_unit", "number");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        // Need at least INHOSPITABLE standing to trade
        return !ctx.repLevel.isAtBest(RepLevel.HOSTILE);
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (ctx.fleet == null) return;

        String commodityId = (String) args.get("commodity_id");
        if (commodityId == null || commodityId.isEmpty()) return;

        float qty = 0f;
        Object qtyObj = args.get("quantity");
        if (qtyObj instanceof Number) qty = ((Number) qtyObj).floatValue();
        qty = Math.min(qty, MAX_QTY);
        if (qty <= 0) return;

        float pricePerUnit = 0f;
        Object priceObj = args.get("price_per_unit");
        if (priceObj instanceof Number) pricePerUnit = ((Number) priceObj).floatValue();
        float totalCost = qty * pricePerUnit;

        CargoAPI npcCargo = ctx.fleet.getCargo();
        CargoAPI playerCargo = Global.getSector().getPlayerFleet().getCargo();

        // Check NPC has enough commodity
        float npcQty = npcCargo.getCommodityQuantity(commodityId);
        float transferQty = Math.min(qty, npcQty);
        if (transferQty <= 0) {
            log.debug("Starlogue: trade_offer skipped — NPC has 0 of " + commodityId);
            return;
        }

        // Check player can afford
        float playerCredits = playerCargo.getCredits().get();
        float actualCost = transferQty * pricePerUnit;
        if (playerCredits < actualCost) {
            // Partial if player can't afford all
            transferQty = (float) Math.floor(playerCredits / pricePerUnit);
            actualCost = transferQty * pricePerUnit;
        }
        if (transferQty <= 0) return;

        npcCargo.removeCommodity(commodityId, transferQty);
        playerCargo.addCommodity(commodityId, transferQty);
        playerCargo.getCredits().add(-actualCost);
        npcCargo.getCredits().add(actualCost);

        log.debug("Starlogue: trade_offer " + (int) transferQty + "x" + commodityId
                + " for " + (int) actualCost + " credits");
    }

    @Override
    public String narrativeNote() { return "Trade executed. Cargo transferred."; }
}
