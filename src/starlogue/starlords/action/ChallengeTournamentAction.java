package starlogue.starlords.action;

import starlogue.action.StarlogueAction;
import starlogue.api.StarlogueAPI;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import starlords.controllers.EventController;
import starlords.person.Lord;
import starlords.person.LordEvent;
import starlords.plugins.TournamentDialogPlugin;
import org.apache.log4j.Logger;
import java.util.Collections;
import java.util.Map;

/**
 * The lord challenges the player to a tournament.
 *
 * Requires an active feast for the lord's faction — tournaments are a feast
 * institution in Star Lords. The Starlogue dialog hands off to
 * TournamentDialogPlugin, which runs the full tournament flow (battle
 * creation, prestige tracking, ranking updates).
 *
 * Stakes are narrative only — the tournament system tracks outcomes via
 * LordEvent.setTournamentWinner() and lord.recordKills() internally.
 */
public class ChallengeTournamentAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(ChallengeTournamentAction.class);

    @Override public String getId() { return "challenge_tournament"; }

    @Override
    public String getDescription() {
        return "You challenge the player to a tournament — a formal fleet engagement "
             + "held in the spirit of the feast. Winner gains prestige and ranking. "
             + "Only available when a feast is active for your faction. "
             + "Describe the terms and what honour is at stake before invoking this. "
             + "stakes_note: one sentence on what honour or recognition the winner earns.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Collections.singletonMap("stakes_note", (Object) "string");
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (!(ctx.lordData instanceof Lord)) return false;
        Lord lord = (Lord) ctx.lordData;
        return EventController.getCurrentFeast(lord.getFaction()) != null;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (!(ctx.lordData instanceof Lord)) return;
        Lord lord = (Lord) ctx.lordData;

        LordEvent feast = EventController.getCurrentFeast(lord.getFaction());
        if (feast == null) {
            log.warn("Starlogue challenge_tournament: feast ended before execution");
            return;
        }

        MemoryEngine.recordEvent(ctx.person, MemoryEvent.GAVE_WORK, 1.0f);
        log.info("Starlogue: " + ctx.person.getNameString()
            + " challenged player to tournament at feast");

        // Hand off to Star Lords' TournamentDialogPlugin.
        // StarlogueDialogPlugin will dismiss itself and launch this on the next frame.
        StarlogueAPI.handoffToPlugin(new TournamentDialogPlugin(feast));
    }

    @Override
    public String narrativeNote() {
        return "The challenge is made. The tournament begins.";
    }
}
