package starlogue.action.fleet;

import com.fs.starfarer.api.campaign.RepLevel;
import org.apache.log4j.Logger;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;

import java.util.Collections;
import java.util.Map;

/** Strong confrontation when fleet markings strongly contradict the claimed identity. */
public class ExposeInconsistencyAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(ExposeInconsistencyAction.class);
    private String lastNote;

    @Override public String getId() { return "expose_inconsistency"; }

    @Override
    public String getDescription() {
        return "Publicly call out a blatant contradiction between claimed identity and visible fleet composition. "
            + "Use sparingly when mismatch is severe.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Collections.emptyMap();
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (ctx.fleet == null || ctx.person == null) return false;
        if (ctx.playerTransponderOn) return false;
        return ctx.fleetSignatureMismatchHint > 0.55f && !ctx.repLevel.isAtBest(RepLevel.HOSTILE);
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.BLUFF_CAUGHT, 1.0f);
        lastNote = "They cite specific hull markings that do not fit your cover — trust is damaged.";
        log.debug("Starlogue: expose_inconsistency");
    }

    @Override
    public String narrativeNote() {
        return lastNote;
    }
}
