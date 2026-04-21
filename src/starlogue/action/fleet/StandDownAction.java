package starlogue.action.fleet;

import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.RepLevel;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import org.apache.log4j.Logger;
import java.util.Collections;
import java.util.Map;

/**
 * The NPC fleet surrenders and disperses — no combat result.
 * Only available when the player fleet is significantly stronger.
 */
public class StandDownAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(StandDownAction.class);

    @Override public String getId() { return "stand_down"; }

    @Override
    public String getDescription() {
        return "The NPC fleet stands down and submits without a fight. "
             + "Use only when the NPC acknowledges they are outmatched and chooses not to fight.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Collections.emptyMap();
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        // Only valid when player is clearly stronger (delta < -0.25 = player has 25%+ more FP)
        return ctx.strengthDelta < -0.25f;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (ctx.fleet == null) return;
        ctx.fleet.clearAssignments();
        ctx.fleet.addAssignment(FleetAssignment.STANDING_DOWN, null, 30f);
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.DEESCALATED, 1.0f);
        log.debug("Starlogue: stand_down executed for " + ctx.person.getNameString());
    }

    @Override public String narrativeNote() { return "They lower their shields. The fleet submits."; }
}
