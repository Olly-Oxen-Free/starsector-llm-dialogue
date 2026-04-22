package starlogue.starlords.action;

import starlogue.action.StarlogueAction;
import starlogue.api.StarlogueAPI;
import starlogue.engine.GameContext;
import starlords.controllers.QuestController;
import starlords.person.Lord;
import starlogue.starlords.QuestDialogPlugin;
import org.apache.log4j.Logger;
import java.util.Collections;
import java.util.Map;

public class RequestQuestAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(RequestQuestAction.class);

    @Override public String getId() { return "request_quest"; }

    @Override
    public String getDescription() {
        return "The lord offers the player a mission — a formal task with a briefing and reward. "
             + "Call this only when the player asks for work and a mission is available. "
             + "Describe the nature of the task (trade, military, underworld) based on the lord's "
             + "personality before invoking. No parameters needed.";
    }

    @Override
    public Map<String, Object> getParameters() { return Collections.emptyMap(); }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (!(ctx.lordData instanceof Lord)) return false;
        Lord lord = (Lord) ctx.lordData;
        return !QuestController.isQuestGiven(lord);
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (!(ctx.lordData instanceof Lord)) return;
        Lord lord = (Lord) ctx.lordData;
        log.info("Starlogue: " + ctx.person.getNameString() + " offering quest to player");
        StarlogueAPI.handoffToPlugin(new QuestDialogPlugin(lord));
    }

    @Override
    public String narrativeNote() { return "The lord has a task for you. The briefing follows."; }
}
