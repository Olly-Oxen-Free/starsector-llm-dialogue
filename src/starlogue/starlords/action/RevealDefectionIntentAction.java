package starlogue.starlords.action;

import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import starlords.controllers.RequestController;
import starlords.person.Lord;
import starlords.person.LordRequest;
import starlords.util.DefectionUtils;
import org.apache.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;

public class RevealDefectionIntentAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(RevealDefectionIntentAction.class);

    @Override public String getId() { return "reveal_defection_intent"; }

    @Override
    public String getDescription() {
        return "The lord confides to the player that they want to leave their faction. "
             + "This creates a formal defection request the player can fulfil through gameplay "
             + "(granting a fief, aiding an escape, etc.). "
             + "Only reveal this after establishing real trust — this information is dangerous. "
             + "grievance: one sentence on what drove this decision.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("grievance", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (!(ctx.lordData instanceof Lord)) return false;
        Lord lord = (Lord) ctx.lordData;
        return DefectionUtils.getAutoBetrayalChance(lord) > 0
            && RequestController.getCurrentDefectionRequest(lord) == null;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (!(ctx.lordData instanceof Lord)) return;
        Lord lord = (Lord) ctx.lordData;
        RequestController.addRequest(new LordRequest(LordRequest.FIEF_FOR_DEFECTION, lord));
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.POLITICALLY_ALLIED, 2.0f);
        log.info("Starlogue: " + ctx.person.getNameString() + " revealed defection intent");
    }

    @Override
    public String narrativeNote() {
        return "The lord has revealed their intent. A formal request now waits for you.";
    }
}
