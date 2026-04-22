package starlogue.starlords.action;

import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import starlords.controllers.LordController;
import starlords.controllers.RelationController;
import starlords.person.Lord;
import org.apache.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The lord adjusts their relationship with another named lord.
 * Useful for political maneuvering — reconciling feuding lords or
 * turning lords against each other.
 */
public class ModifyLordRelationAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(ModifyLordRelationAction.class);

    @Override public String getId() { return "modify_lord_relation"; }

    @Override
    public String getDescription() {
        return "This lord adjusts their relationship with another lord at the player's request. "
             + "target_lord_first_name: the first name of the lord to adjust relations with. "
             + "amount: integer change (-50 to +50). Negative worsens, positive improves their relationship. "
             + "Requires the player to have earned significant trust.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("target_lord_first_name", "string");
        p.put("amount", "number");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        return ctx.memoryScore > 20f;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (!(ctx.lordData instanceof Lord)) return;
        Lord actor = (Lord) ctx.lordData;

        String targetName = (String) args.get("target_lord_first_name");
        if (targetName == null || targetName.isEmpty()) return;

        Lord target = LordController.getLordByFirstName(targetName);
        if (target == null) {
            log.warn("Starlogue: modify_lord_relation — lord '" + targetName + "' not found");
            return;
        }

        int rawAmount = 0;
        Object amtObj = args.get("amount");
        if (amtObj instanceof Number) rawAmount = ((Number) amtObj).intValue();
        int amount = Math.max(-50, Math.min(50, rawAmount));

        RelationController.modifyRelation(actor, target, amount);
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.GAVE_WORK, 2.0f);
        log.info("Starlogue: modify_lord_relation " + actor.getLordAPI().getNameString()
            + " ↔ " + target.getLordAPI().getNameString() + " by " + amount);
    }

    @Override
    public String narrativeNote() { return null; }
}
