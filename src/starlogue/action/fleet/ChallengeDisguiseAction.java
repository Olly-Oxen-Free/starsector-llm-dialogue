package starlogue.action.fleet;

import com.fs.starfarer.api.campaign.RepLevel;
import org.apache.log4j.Logger;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;

import java.util.Map;

/** NPC presses the player on a weak cover story (transponder off + visual mismatch). */
public class ChallengeDisguiseAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(ChallengeDisguiseAction.class);
    private String lastNote;

    @Override public String getId() { return "challenge_disguise"; }

    @Override
    public String getDescription() {
        return "Challenge an inconsistent disguise or cover story using visible fleet cues. "
            + "Optional reason (string). Applies a small negative memory signal when the situation warrants.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new java.util.LinkedHashMap<String, Object>();
        p.put("reason", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (ctx.fleet == null || ctx.person == null) return false;
        if (ctx.playerTransponderOn) return false;
        return ctx.fleetSignatureMismatchHint > 0.25f
                && (ctx.repLevel == null || !ctx.repLevel.isAtBest(RepLevel.HOSTILE));
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.LIED_DETECTED, 0.5f);
        Object r = args.get("reason");
        lastNote = "They narrow their eyes. "
            + (r != null && !String.valueOf(r).isBlank() ? String.valueOf(r) : "Your story does not match what they see.");
        log.debug("Starlogue: challenge_disguise");
    }

    @Override
    public String narrativeNote() {
        return lastNote;
    }
}
