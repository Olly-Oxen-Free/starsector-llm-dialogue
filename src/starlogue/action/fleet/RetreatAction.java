package starlogue.action.fleet;

import com.fs.starfarer.api.campaign.FleetAssignment;
import com.fs.starfarer.api.campaign.RepLevel;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import org.apache.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;

public class RetreatAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(RetreatAction.class);

    @Override public String getId() { return "retreat"; }

    @Override
    public String getDescription() {
        return "The NPC fleet disengages and withdraws from the encounter. "
             + "Use when the NPC chooses to avoid conflict.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("reason", "string");
        return p;
    }

    @Override public boolean isBluffable() { return true; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        // A stronger hostile NPC won't retreat
        if (ctx.strengthDelta > 0.20f
                && ctx.repLevel != null
                && ctx.repLevel.isAtBest(RepLevel.HOSTILE)) {
            return false;
        }
        return true;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (ctx.fleet != null) {
            ctx.fleet.clearAssignments();
            ctx.fleet.addAssignment(FleetAssignment.GO_TO_LOCATION_AND_DESPAWN, null, 5f);
        }
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.DEESCALATED, 1.0f);
        log.debug("Starlogue: retreat executed for " + ctx.person.getNameString());
    }

    @Override public String narrativeNote() { return "They power down weapons and break off."; }
}
