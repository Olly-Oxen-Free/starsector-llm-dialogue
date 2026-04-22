package starlogue.starlords.action;

import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import starlords.controllers.LordController;
import starlords.controllers.PoliticsController;
import starlords.faction.LawProposal;
import starlords.person.Lord;
import org.apache.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;

public class VetoProposalAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(VetoProposalAction.class);

    @Override public String getId() { return "veto_proposal"; }

    @Override
    public String getDescription() {
        return "The player, as a lord in this faction, moves to kill the current council proposal. "
             + "The lord facilitates this veto. Triggers relation consequences with the proposal's supporters. "
             + "Only appropriate when the player has standing in this faction and has convinced the lord this is necessary. "
             + "justification: one sentence explaining the veto.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("justification", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (!(ctx.lordData instanceof Lord)) return false;
        Lord lord = (Lord) ctx.lordData;
        LawProposal p = PoliticsController.getCurrProposal(lord.getFaction());
        if (p == null) return false;
        Lord playerLord = LordController.getPlayerLord();
        return playerLord != null && playerLord.getFaction().equals(lord.getFaction());
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (!(ctx.lordData instanceof Lord)) return;
        Lord lord = (Lord) ctx.lordData;
        LawProposal p = PoliticsController.getCurrProposal(lord.getFaction());
        if (p == null) {
            log.warn("Starlogue veto_proposal: proposal ended before execution");
            return;
        }
        PoliticsController.vetoProposal(lord.getFaction());
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.SIGNED_AGREEMENT, 3.0f);
        log.info("Starlogue: " + ctx.person.getNameString() + " facilitated veto of: " + p.getSummary());
    }

    @Override
    public String narrativeNote() { return "The proposal has been vetoed. The council falls silent."; }
}
