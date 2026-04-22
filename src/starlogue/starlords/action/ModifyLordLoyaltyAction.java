package starlogue.starlords.action;

import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import starlords.controllers.RelationController;
import starlords.person.Lord;
import org.apache.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adjust the lord's loyalty to their current faction.
 * High loyalty: committed. Low loyalty: open to defection.
 * Positive amount = more loyal. Negative = less loyal (may eventually defect).
 */
public class ModifyLordLoyaltyAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(ModifyLordLoyaltyAction.class);

    @Override public String getId() { return "modify_lord_loyalty"; }

    @Override
    public String getDescription() {
        return "Adjust this lord's loyalty to their current faction. "
             + "amount: integer (-30 to +30). Negative reduces loyalty, potentially enabling defection. "
             + "Use to deepen a trusted alliance or sow doubt in a dissatisfied lord. "
             + "This is a sensitive political act — use only when the moment is right.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("amount", "number");
        p.put("reason", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (!(ctx.lordData instanceof Lord)) return false;
        Lord lord = (Lord) ctx.lordData;
        // Need positive history to reduce loyalty, or lord already wants to defect
        return ctx.memoryScore > 30f || lord.wantsToDefect();
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (!(ctx.lordData instanceof Lord)) return;
        Lord lord = (Lord) ctx.lordData;

        int rawAmount = 0;
        Object amtObj = args.get("amount");
        if (amtObj instanceof Number) rawAmount = ((Number) amtObj).intValue();
        int amount = Math.max(-30, Math.min(30, rawAmount));

        RelationController.modifyLoyalty(lord, amount);

        MemoryEvent evt = amount > 0 ? MemoryEvent.POLITICALLY_ALLIED : MemoryEvent.OFFENDED;
        MemoryEngine.recordEvent(ctx.person, evt, 2.0f);
        log.info("Starlogue: modify_lord_loyalty " + ctx.person.getNameString() + " by " + amount);
    }

    @Override
    public String narrativeNote() { return null; }
}
