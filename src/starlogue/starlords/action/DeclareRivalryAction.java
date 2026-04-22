package starlogue.starlords.action;

import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import starlords.controllers.LordController;
import starlords.controllers.RelationController;
import starlords.person.Lord;
import org.apache.log4j.Logger;
import java.util.Collections;
import java.util.Map;

/**
 * The lord formally declares the player a rival.
 * Records deep hostile memory and reduces lord-to-player relationship,
 * making the lord actively hostile in future encounters.
 *
 * isBluffable: true — aggressive/reckless lords may threaten rivalry they don't follow through on.
 */
public class DeclareRivalryAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(DeclareRivalryAction.class);

    @Override public String getId() { return "declare_rivalry"; }

    @Override
    public String getDescription() {
        return "The lord formally declares the player a rival — they will remember this and "
             + "treat the player as an enemy in future encounters. "
             + "Use when the NPC has reached a point of no return in hostility.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Collections.emptyMap();
    }

    @Override public boolean isBluffable() { return true; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        // Available when relations have significantly deteriorated
        return ctx.memoryScore < -20f || ctx.repValue < -0.3f;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (!(ctx.lordData instanceof Lord)) return;
        Lord lord = (Lord) ctx.lordData;

        // Get the player lord object if it exists (Star Lords tracks the player as a lord)
        Lord playerLord = LordController.getLordOrPlayerById(
            com.fs.starfarer.api.Global.getSector().getPlayerPerson().getId());
        if (playerLord != null) {
            RelationController.modifyRelation(lord, playerLord, -50);
        }

        // Deep hostile memory — 3x multiplier, long-lasting
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.HOSTILE_ACT, 3.0f);
        log.info("Starlogue: " + ctx.person.getNameString() + " declared rivalry with player");
    }

    @Override
    public String narrativeNote() { return "A rival is made. They will not forget this."; }
}
