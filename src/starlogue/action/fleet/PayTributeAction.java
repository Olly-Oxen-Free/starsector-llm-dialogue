package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import org.apache.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The NPC fleet pays the player credits to avoid conflict.
 * Only available when the player fleet is significantly stronger.
 */
public class PayTributeAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(PayTributeAction.class);

    private static final float MAX_TRIBUTE = 150_000f;
    private static final float NPC_RESERVE_FRACTION = 0.15f; // keep 15% of NPC credits

    @Override public String getId() { return "pay_tribute"; }

    @Override
    public String getDescription() {
        return "The NPC fleet pays the player a sum of credits to avoid destruction or combat. "
             + "Provide an amount in credits. NPC must outmatched and willing to pay.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("amount", "number");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        // NPC pays only when player clearly stronger and NPC has enough rep not to be purely hostile
        return ctx.strengthDelta < -0.20f && !ctx.repLevel.isAtBest(RepLevel.VENGEFUL);
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (ctx.fleet == null) return;

        float requested = 0f;
        Object amtObj = args.get("amount");
        if (amtObj instanceof Number) requested = ((Number) amtObj).floatValue();
        if (requested <= 0) return;

        CargoAPI npcCargo = ctx.fleet.getCargo();
        float npcCredits = npcCargo.getCredits().get();
        float spendable = npcCredits * (1f - NPC_RESERVE_FRACTION);
        float actual = Math.min(requested, Math.min(spendable, MAX_TRIBUTE));
        if (actual <= 0) {
            log.debug("Starlogue: pay_tribute skipped — NPC has insufficient credits");
            return;
        }

        npcCargo.getCredits().add(-actual);
        CargoAPI playerCargo = Global.getSector().getPlayerFleet().getCargo();
        playerCargo.getCredits().add(actual);

        MemoryEngine.recordEvent(ctx.person, MemoryEvent.DEESCALATED, 0.5f);
        log.debug("Starlogue: pay_tribute " + (int) actual + " credits from " + ctx.person.getNameString());
    }

    @Override
    public String narrativeNote() { return "Credits transferred to your account."; }
}
