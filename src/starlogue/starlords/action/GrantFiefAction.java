package starlogue.starlords.action;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import starlords.controllers.FiefController;
import starlords.controllers.LordController;
import starlords.person.Lord;
import starlogue.starlords.FiefValueCalculator;
import org.apache.log4j.Logger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lord grants the player one of their fiefs.
 *
 * PRICE STRUCTURE:
 *   The lord always records a favor owed (mandatory — never waived).
 *   Primary conditions (at least one required):
 *     (A) Player joins lord's faction
 *     (B) Player gives an equivalent market in exchange
 *   Additional payment on top:
 *     - Credits
 *     - Ships from player's fleet (removed from player, lord gains wealth)
 *     - Commodities from player's cargo
 *
 * Fief value is computed by FiefValueCalculator and injected into context so
 * the LLM knows the required payment amount. execute() validates the offered
 * basket meets the required threshold (with 30% tolerance) before proceeding.
 *
 * The LLM must name terms explicitly in conversation and only invoke this
 * action AFTER the player has explicitly agreed.
 */
public class GrantFiefAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(GrantFiefAction.class);

    // Validation tolerance: offered payment must be >= required * this factor
    private static final float PAYMENT_TOLERANCE = 0.70f;

    @Override public String getId() { return "grant_fief"; }

    @Override
    public String getDescription() {
        return "You agree to transfer one of your fiefs to the player. "
             + "A favor owed is ALWAYS recorded regardless of other terms — this is non-negotiable. "
             + "You MUST also require at least one primary condition: "
             + "(A) requires_join_faction: true — the player joins your faction, AND/OR "
             + "(B) payment_exchange_fief: the player gives you an equivalent market they own. "
             + "On top of the primary conditions, demand additional payment "
             + "(credits, ships, commodities) to reach the required total shown in your context. "
             + "NEVER grant a fief for credits or ships alone. "
             + "Name your exact terms in conversation first — invoke this action ONLY after "
             + "the player has explicitly agreed. "
             + "Parameters: "
             + "granted_fief_name: one of your fief names (from context). "
             + "requires_join_faction: true if player must join your faction. "
             + "payment_exchange_fief: player's market name they are giving you (empty if none). "
             + "payment_credits: additional credits (0 if none). "
             + "payment_ships: semicolon-separated ship names from player fleet (empty if none). "
             + "payment_commodities: 'fuel:200;supplies:100' format (empty if none). "
             + "favor_note: one sentence describing what you may call in as a favor later.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("granted_fief_name",       "string");
        p.put("requires_join_faction",   "boolean");
        p.put("payment_exchange_fief",   "string");
        p.put("payment_credits",         "number");
        p.put("payment_ships",           "string");  // semicolon-separated ship names
        p.put("payment_commodities",     "string");  // "commodityId:amount;commodityId:amount"
        p.put("favor_note",              "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (!(ctx.lordData instanceof Lord)) return false;
        Lord lord = (Lord) ctx.lordData;
        return ctx.memoryScore > 60f
            && lord.getFiefs() != null
            && lord.getFiefs().size() > 1;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (!(ctx.lordData instanceof Lord)) return;
        Lord lord = (Lord) ctx.lordData;
        Lord playerLord = LordController.getPlayerLord();
        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
        if (playerFleet == null) return;
        CargoAPI playerCargo = playerFleet.getCargo();

        // ── Parse parameters ─────────────────────────────────────────────────
        String grantedFiefName   = str(args, "granted_fief_name");
        boolean requiresJoin     = Boolean.TRUE.equals(args.get("requires_join_faction"));
        String exchangeFiefName  = str(args, "payment_exchange_fief");
        float payCredits         = flt(args, "payment_credits");
        String payShipsRaw       = str(args, "payment_ships");
        String payCommoditiesRaw = str(args, "payment_commodities");
        String favorNote         = str(args, "favor_note");

        // ── Validate: lord's fief ────────────────────────────────────────────
        MarketAPI grantedMarket = findFiefByName(lord, grantedFiefName);
        if (grantedMarket == null) {
            log.warn("Starlogue grant_fief: fief '" + grantedFiefName
                + "' not found on " + lord.getLordAPI().getNameString());
            return;
        }
        float required = FiefValueCalculator.computeRequiredPayment(
            grantedMarket, ctx.memoryScore, lord.getPlayerRel());

        // ── Validate: exchange fief ──────────────────────────────────────────
        MarketAPI exchangeMarket = null;
        float exchangeFiefValue = 0f;
        if (exchangeFiefName != null && !exchangeFiefName.isEmpty()) {
            if (playerLord == null) {
                log.warn("Starlogue grant_fief: exchange fief requested but player has no lord object");
                return;
            }
            exchangeMarket = findFiefByName(playerLord, exchangeFiefName);
            if (exchangeMarket == null) {
                log.warn("Starlogue grant_fief: player fief '" + exchangeFiefName + "' not found");
                return;
            }
            exchangeFiefValue = FiefValueCalculator.computeBaseValue(exchangeMarket);
        }

        // ── Validate: credits ────────────────────────────────────────────────
        if (payCredits > 0 && playerCargo.getCredits().get() < payCredits) {
            log.warn("Starlogue grant_fief: player cannot afford " + payCredits + " credits");
            return;
        }

        // ── Resolve: ships ───────────────────────────────────────────────────
        List<FleetMemberAPI> shipsToTransfer = new ArrayList<FleetMemberAPI>();
        float shipValue = 0f;
        if (payShipsRaw != null && !payShipsRaw.trim().isEmpty()) {
            for (String name : payShipsRaw.split(";")) {
                name = name.trim();
                if (name.isEmpty()) continue;
                FleetMemberAPI member = findShipByName(playerFleet, name);
                if (member == null) {
                    log.warn("Starlogue grant_fief: ship '" + name + "' not found in player fleet");
                    continue;
                }
                shipsToTransfer.add(member);
                shipValue += FiefValueCalculator.estimateShipValue(member);
            }
        }

        // ── Resolve: commodities ─────────────────────────────────────────────
        Map<String, Float> commoditiesToPay = new LinkedHashMap<String, Float>();
        float commodityValue = 0f;
        if (payCommoditiesRaw != null && !payCommoditiesRaw.trim().isEmpty()) {
            for (String entry : payCommoditiesRaw.split(";")) {
                entry = entry.trim();
                String[] parts = entry.split(":");
                if (parts.length != 2) continue;
                String commodityId = parts[0].trim();
                float amount;
                try { amount = Float.parseFloat(parts[1].trim()); }
                catch (NumberFormatException e) { continue; }
                float available = playerCargo.getCommodityQuantity(commodityId);
                if (available < amount) {
                    log.warn("Starlogue grant_fief: insufficient " + commodityId
                        + " (need " + amount + ", have " + available + ")");
                    return;
                }
                commoditiesToPay.put(commodityId, amount);
                commodityValue += FiefValueCalculator.estimateCommodityValue(commodityId, amount);
            }
        }

        // ── Validate: total offered payment meets required ────────────────────
        float totalOffered = payCredits + shipValue + commodityValue + exchangeFiefValue;
        // Exchange fief and join-faction are primary conditions, not pure credit value —
        // if either primary condition is met, apply a credit toward the total
        boolean hasPrimary = requiresJoin || exchangeMarket != null;
        if (!hasPrimary) {
            log.warn("Starlogue grant_fief: no primary condition (join faction or exchange fief)");
            return;
        }
        // Join-faction credit: treated as 40% of the required payment toward the total
        float joinFactionCredit = requiresJoin ? required * 0.40f : 0f;
        float effectiveOffered = totalOffered + joinFactionCredit;
        if (effectiveOffered < required * PAYMENT_TOLERANCE) {
            log.warn("Starlogue grant_fief: payment insufficient — offered "
                + FiefValueCalculator.formatK(effectiveOffered)
                + "cr vs required "
                + FiefValueCalculator.formatK(required) + "cr");
            return;
        }

        // ── All validated — execute ───────────────────────────────────────────

        // 1. Record the favor (always — non-negotiable)
        if (favorNote != null && !favorNote.isEmpty()) {
            lord.getLordDataHolder().setString("starlogue_favor_owed", favorNote);
            lord.saveLordDataHolder();
        }
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.SIGNED_AGREEMENT, 5.0f);

        // 2. Deduct credits
        if (payCredits > 0) {
            playerCargo.getCredits().subtract(payCredits);
            lord.addWealth(payCredits * 0.5f);
        }

        // 3. Transfer ships: remove from player fleet; lord gains their wealth value
        for (FleetMemberAPI ship : shipsToTransfer) {
            playerFleet.getFleetData().removeFleetMember(ship);
            lord.addWealth(FiefValueCalculator.estimateShipValue(ship) * 0.3f);
            log.info("Starlogue grant_fief: transferred ship " + ship.getShipName()
                + " from player to " + lord.getLordAPI().getNameString());
        }

        // 4. Deduct commodities
        for (Map.Entry<String, Float> entry : commoditiesToPay.entrySet()) {
            playerCargo.addCommodity(entry.getKey(), -entry.getValue());
            lord.addWealth(FiefValueCalculator.estimateCommodityValue(
                entry.getKey(), entry.getValue()) * 0.4f);
        }

        // 5. Transfer exchange fief: player → lord
        if (exchangeMarket != null && playerLord != null) {
            transferFief(playerLord, lord, exchangeMarket);
            log.info("Starlogue grant_fief: player gave "
                + exchangeMarket.getName() + " to " + lord.getLordAPI().getNameString());
        }

        // 6. Apply faction join: set rep to FAVORABLE minimum
        if (requiresJoin) {
            FactionAPI lordFaction = lord.getFaction();
            if (lordFaction != null) {
                float current = Global.getSector().getPlayerFaction()
                    .getRelationship(lordFaction.getId());
                if (current < 0.3f) {
                    Global.getSector().getPlayerFaction()
                        .setRelationship(lordFaction.getId(), 0.3f);
                }
            }
        }

        // 7. Transfer granted fief: lord → player
        if (playerLord != null) {
            transferFief(lord, playerLord, grantedMarket);
        } else {
            FiefController.playerTransferFief(lord, grantedMarket);
        }

        log.info("Starlogue grant_fief: " + lord.getLordAPI().getNameString()
            + " granted " + grantedMarket.getName() + " to player. "
            + "Favor: " + favorNote);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Transfer a market between two lord objects.
     * Keeps lord.fiefs list AND FiefController.fiefOwner map in sync —
     * they are separate state that must both be updated.
     */
    private static void transferFief(Lord from, Lord to, MarketAPI market) {
        from.removeFief(market);
        FiefController.setOwner(market, to.getLordAPI().getId());
        to.addFief(market);
    }

    /** Find a market owned by this lord by display name (case-insensitive). */
    private static MarketAPI findFiefByName(Lord lord, String name) {
        if (name == null || name.isEmpty()) return null;
        for (SectorEntityToken token : lord.getFiefs()) {
            MarketAPI m = token.getMarket();
            if (m != null && m.getName().equalsIgnoreCase(name)) return m;
        }
        return null;
    }

    /** Find a fleet member by ship name, falling back to hull ID if no match. */
    private static FleetMemberAPI findShipByName(CampaignFleetAPI fleet, String name) {
        // First pass: exact ship name match
        for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
            if (m.isFlagship()) continue;
            if (name.equalsIgnoreCase(m.getShipName())) return m;
        }
        // Second pass: hull ID match (allows "onslaught" to match "Onslaught-class")
        for (FleetMemberAPI m : fleet.getFleetData().getMembersListCopy()) {
            if (m.isFlagship()) continue;
            if (m.getHullId().equalsIgnoreCase(name)) return m;
        }
        return null;
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String ? (String) v : null;
    }

    private static float flt(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof Number ? ((Number) v).floatValue() : 0f;
    }

    @Override
    public String narrativeNote() {
        return "The fief changes hands. A binding agreement — and a favor owed — is made.";
    }
}
