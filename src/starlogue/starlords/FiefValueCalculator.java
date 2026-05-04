package starlogue.starlords;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import starlogue.engine.FleetSnapshotFormatter;
import starlords.controllers.FiefController;
import starlords.person.Lord;
import java.util.HashMap;
import java.util.Map;

/**
 * Computes fief and payment-item values for GrantFiefAction negotiation.
 *
 * Base fief value is derived from market size, augmented by annual tax+trade
 * income. A flat markup and opinion modifier produce the required payment price.
 *
 * Payment items (credits, ships, commodities, exchange fiefs) are estimated
 * at the same scale so the LLM can request a matching basket.
 */
public final class FiefValueCalculator {

    // Base credit value by market size (1–6)
    private static final float[] BASE_BY_SIZE = {
        0f,
        60_000f,    // size 1 — outpost
        180_000f,   // size 2
        500_000f,   // size 3 — small colony
        1_200_000f, // size 4
        3_000_000f, // size 5
        7_000_000f  // size 6 — core world
    };

    // Monthly-income-to-capital multiplier (12 months × value weight)
    private static final float INCOME_CAPITALISATION = 12f;

    // Lord always marks up above cost
    private static final float FLAT_MARKUP = 1.30f;

    // Credits granted per deployment point when valuing ships
    public static final float CREDITS_PER_DP = 10_000f;

    // Hardcoded base commodity prices (credits per unit)
    private static final Map<String, Float> COMMODITY_PRICES;
    static {
        COMMODITY_PRICES = new HashMap<String, Float>();
        COMMODITY_PRICES.put("fuel",            75f);
        COMMODITY_PRICES.put("supplies",       100f);
        COMMODITY_PRICES.put("crew",           100f);
        COMMODITY_PRICES.put("heavy_machinery",350f);
        COMMODITY_PRICES.put("metals",          60f);
        COMMODITY_PRICES.put("rare_metals",    300f);
        COMMODITY_PRICES.put("organics",        50f);
        COMMODITY_PRICES.put("drugs",          200f);
        COMMODITY_PRICES.put("domestic_goods", 125f);
        COMMODITY_PRICES.put("luxury_goods",   400f);
        COMMODITY_PRICES.put("food",            40f);
        COMMODITY_PRICES.put("hand_weapons",   150f);
        COMMODITY_PRICES.put("marines",      1_500f);
    }

    private FiefValueCalculator() {}

    // ── Fief valuation ────────────────────────────────────────────────────────

    /** Raw market value, no markup — used for exchange-fief credit equivalent. */
    public static float computeBaseValue(MarketAPI market) {
        int size = Math.max(1, Math.min(6, market.getSize()));
        float base = BASE_BY_SIZE[size];
        float monthlyIncome = FiefController.getTax(market) + FiefController.getTrade(market);
        return base + monthlyIncome * INCOME_CAPITALISATION;
    }

    /**
     * What the lord demands in total payment to grant this market.
     * Applies flat markup, then adjusts by the lord's opinion of the player.
     *
     * @param memoryScore  Starlogue MemoryEngine score for this lord
     * @param playerRel    Star Lords lord.getPlayerRel() value
     */
    public static float computeRequiredPayment(MarketAPI market, float memoryScore, int playerRel) {
        float base = computeBaseValue(market) * FLAT_MARKUP;
        float opinion = memoryScore * 0.5f + playerRel;
        float multiplier;
        if      (opinion > 80f)  multiplier = 0.75f;  // deep trust — generous terms
        else if (opinion > 40f)  multiplier = 0.90f;
        else if (opinion > -10f) multiplier = 1.00f;
        else if (opinion > -40f) multiplier = 1.20f;
        else                     multiplier = 1.45f;  // deep distrust — extortionate
        return base * multiplier;
    }

    // ── Payment item valuation ────────────────────────────────────────────────

    /** Credit value of a single ship (based on deployment points). */
    public static float estimateShipValue(FleetMemberAPI member) {
        return member.getDeploymentPointsCost() * CREDITS_PER_DP;
    }

    /** Credit value of a quantity of a commodity. */
    public static float estimateCommodityValue(String commodityId, float amount) {
        Float price = COMMODITY_PRICES.get(commodityId);
        return price != null ? price * amount : 0f;
    }

    /** Credit equivalent of a fief the player owns (at base value, no markup). */
    public static float estimateExchangeFiefValue(Lord playerLord, String fiefName) {
        if (playerLord == null || fiefName == null) return 0f;
        for (SectorEntityToken token : playerLord.getFiefs()) {
            MarketAPI m = token.getMarket();
            if (m != null && m.getName().equalsIgnoreCase(fiefName)) {
                return computeBaseValue(m);
            }
        }
        return 0f;
    }

    // ── Context helpers ───────────────────────────────────────────────────────

    /**
     * Build a compact context note listing the player's fleet ships the lord
     * could request, sorted by value descending, capped at maxShips entries.
     * Excludes flagship and stations.
     */
    public static String buildFleetNote(CampaignFleetAPI fleet, int maxShips) {
        if (fleet == null) return null;
        String brief = FleetSnapshotFormatter.formatFleetBrief(fleet, maxShips);
        if (brief == null || brief.isEmpty() || "(no fleet)".equals(brief)) return null;
        return "Player fleet (line-of-sight summary — lord may request ships as payment): " + brief;
    }

    /**
     * Build a compact context note listing the player's notable cargo.
     * Only includes commodities with value >= minValue per stack.
     */
    public static String buildCargoNote(CargoAPI cargo, float minStackValue) {
        if (cargo == null) return null;
        // Check common commodities
        String[] ids = {"fuel","supplies","marines","heavy_machinery","rare_metals",
                         "metals","drugs","luxury_goods","domestic_goods","crew",
                         "organics","food","hand_weapons"};
        StringBuilder sb = new StringBuilder("Player's cargo (lord could request as payment): ");
        boolean any = false;
        for (String id : ids) {
            float qty = cargo.getCommodityQuantity(id);
            if (qty < 1f) continue;
            float stackValue = estimateCommodityValue(id, qty);
            if (stackValue < minStackValue) continue;
            if (any) sb.append(", ");
            sb.append(id.replace("_", " ")).append(" x").append((int)qty)
              .append(" (~").append(formatK(stackValue)).append("cr)");
            any = true;
        }
        return any ? sb.toString() : null;
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    public static String formatK(float value) {
        if (value >= 1_000_000f) return String.format("%.1fM", value / 1_000_000f);
        if (value >= 1_000f)     return String.format("%.0fK", value / 1_000f);
        return String.format("%.0f", value);
    }
}
