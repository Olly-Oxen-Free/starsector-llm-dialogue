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

public class ForcePassProposalAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(ForcePassProposalAction.class);

    @Override public String getId() { return "force_pass_proposal"; }

    @Override
    public String getDescription() {
        return "The player moves to force the current council proposal through regardless of vote count, "
             + "with the lord's backing. Carries significant political cost with opposers. "
             + "Only appropriate when the player has standing in this faction. "
             + "justification: one sentence explaining the decision.";
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
            log.warn("Starlogue force_pass_proposal: proposal ended before execution");
            return;
        }
        PoliticsController.forcePassProposal(lord.getFaction());
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.SIGNED_AGREEMENT, 3.0f);
        log.info("Starlogue: " + ctx.person.getNameString() + " force-passed: " + p.getSummary());
    }

    @Override
    public String narrativeNote() { return "The proposal passes by decree. The council will remember this."; }
}
