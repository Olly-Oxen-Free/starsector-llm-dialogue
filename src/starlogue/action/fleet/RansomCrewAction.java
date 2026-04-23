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
 * The NPC fleet returns captured crew in exchange for credits.
 * Only available when the NPC fleet actually holds crew (getCrew() > 0).
 */
public class RansomCrewAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(RansomCrewAction.class);

    private static final float MAX_RANSOM = 100_000f;
    private static final float CREDITS_PER_CREW = 500f; // default if not negotiated

    @Override public String getId() { return "ransom_crew"; }

    @Override
    public String getDescription() {
        return "The NPC fleet releases captured crew in exchange for a ransom payment. "
             + "Only use if the NPC fleet has captured crew. "
             + "amount is total credits paid for crew_count crew members.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("crew_count", "number");
        p.put("amount", "number");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        // Need crew in NPC cargo and at least minimal standing
        if (ctx.fleet == null) return false;
        return ctx.fleet.getCargo().getCrew() > 0
                && (ctx.repLevel == null || !ctx.repLevel.isAtBest(RepLevel.VENGEFUL));
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (ctx.fleet == null) return;

        int crewCount = 0;
        Object ccObj = args.get("crew_count");
        if (ccObj instanceof Number) crewCount = ((Number) ccObj).intValue();

        float amount = 0f;
        Object amtObj = args.get("amount");
        if (amtObj instanceof Number) amount = ((Number) amtObj).floatValue();

        CargoAPI npcCargo = ctx.fleet.getCargo();
        CargoAPI playerCargo = Global.getSector().getPlayerFleet().getCargo();

        int npcCrew = npcCargo.getCrew();
        int transfer = Math.min(crewCount, npcCrew);
        if (transfer <= 0) return;

        float clampedAmount = Math.min(amount, MAX_RANSOM);

        // Deduct player credits, transfer crew
        playerCargo.getCredits().add(-clampedAmount);
        npcCargo.getCredits().add(clampedAmount);
        npcCargo.addCrew(-transfer);
        playerCargo.addCrew(transfer);

        MemoryEngine.recordEvent(ctx.person, MemoryEvent.DEESCALATED, 0.5f);
        log.debug("Starlogue: ransom_crew " + transfer + " crew for " + (int) clampedAmount + " credits");
    }

    @Override
    public String narrativeNote() { return "Your crew is released and boarding now."; }
}
