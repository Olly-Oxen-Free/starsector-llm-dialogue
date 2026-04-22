package starlogue.starlords.action;

import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import starlords.controllers.LordController;
import starlords.person.Lord;
import org.apache.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;

public class ReleasePrisonerAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(ReleasePrisonerAction.class);

    @Override public String getId() { return "release_prisoner"; }

    @Override
    public String getDescription() {
        return "The lord releases the player's captured lord from captivity. "
             + "Only invoke after the player has negotiated terms — demand something in return and describe the release. "
             + "terms_summary: one sentence on what was agreed.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("terms_summary", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (!(ctx.lordData instanceof Lord)) return false;
        Lord lord = (Lord) ctx.lordData;
        Lord playerLord = LordController.getPlayerLord();
        if (playerLord == null) return false;
        return lord.getPrisoners().contains(playerLord.getLordAPI().getId());
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (!(ctx.lordData instanceof Lord)) return;
        Lord lord = (Lord) ctx.lordData;
        Lord playerLord = LordController.getPlayerLord();
        if (playerLord == null) return;
        String playerId = playerLord.getLordAPI().getId();
        if (!lord.getPrisoners().contains(playerId)) {
            log.warn("Starlogue release_prisoner: player not in prisoner list");
            return;
        }
        lord.removePrisoner(playerId);
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.GAVE_WORK, 2.0f);
        log.info("Starlogue: " + ctx.person.getNameString() + " released player lord");
    }

    @Override
    public String narrativeNote() { return "The prisoner walks free. Terms have been satisfied."; }
}
