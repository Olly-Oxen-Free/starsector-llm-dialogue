package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import org.apache.log4j.Logger;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic credits transfer negotiated in dialog.
 */
public class TransferCreditsAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(TransferCreditsAction.class);
    private static final float MAX_TRANSFER = 1_000_000f;

    @Override
    public String getId() {
        return "transfer_credits";
    }

    @Override
    public String getDescription() {
        return "Transfer credits between fleets after terms are agreed. "
            + "amount is the credit amount, direction is either npc_to_player or player_to_npc.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("amount", "number");
        p.put("direction", "string");
        return p;
    }

    @Override
    public java.util.Map<String, String> getParameterDescriptions() {
        java.util.Map<String, String> d = new java.util.LinkedHashMap<>();
        d.put("amount", "Credits to transfer. Positive number.");
        d.put("direction", "Transfer direction: 'npc_to_player' (NPC gives credits to player) or 'player_to_npc' (player pays NPC).");
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
        float requested = asFloat(args.get("amount"));
        if (requested <= 0f) return;
        float amount = Math.min(requested, MAX_TRANSFER);
        String direction = String.valueOf(args.get("direction"));
        boolean npcToPlayer = !"player_to_npc".equalsIgnoreCase(direction);

        CargoAPI npc = ctx.fleet.getCargo();
        CargoAPI player = Global.getSector().getPlayerFleet().getCargo();
        if (npcToPlayer) {
            float actual = Math.min(amount, npc.getCredits().get());
            if (actual <= 0f) return;
            npc.getCredits().add(-actual);
            player.getCredits().add(actual);
            log.debug("Starlogue: transfer_credits npc_to_player=" + (int) actual);
        } else {
            float actual = Math.min(amount, player.getCredits().get());
            if (actual <= 0f) return;
            player.getCredits().add(-actual);
            npc.getCredits().add(actual);
            log.debug("Starlogue: transfer_credits player_to_npc=" + (int) actual);
        }
    }

    @Override
    public String narrativeNote() {
        return "Credits transferred.";
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
