package starlogue.starlords.action;

import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import starlords.controllers.PoliticsController;
import starlords.faction.LawProposal;
import starlords.person.Lord;
import org.apache.log4j.Logger;
import java.util.LinkedHashMap;
import java.util.Map;

public class PledgeAgainstProposalAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(PledgeAgainstProposalAction.class);

    @Override public String getId() { return "pledge_against_proposal"; }

    @Override
    public String getDescription() {
        return "The lord commits their political weight to opposing the current council proposal. "
             + "Call only if the lord genuinely opposes it after discussion. Binding commitment. "
             + "rationale: one sentence on why the lord is pledging opposition.";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> p = new LinkedHashMap<String, Object>();
        p.put("rationale", "string");
        return p;
    }

    @Override public boolean isBluffable() { return false; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        if (!(ctx.lordData instanceof Lord)) return false;
        Lord lord = (Lord) ctx.lordData;
        LawProposal p = PoliticsController.getCurrProposal(lord.getFaction());
        if (p == null) return false;
        String id = lord.getLordAPI().getId();
        return !p.getPledgedFor().contains(id) && !p.getPledgedAgainst().contains(id);
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (!(ctx.lordData instanceof Lord)) return;
        Lord lord = (Lord) ctx.lordData;
        LawProposal p = PoliticsController.getCurrProposal(lord.getFaction());
        if (p == null) {
            log.warn("Starlogue pledge_against_proposal: proposal ended before execution");
            return;
        }
        p.getPledgedAgainst().add(lord.getLordAPI().getId());
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.POLITICALLY_ALLIED, 1.0f);
        log.info("Starlogue: " + ctx.person.getNameString() + " pledged AGAINST: " + p.getSummary());
    }

    @Override
    public String narrativeNote() { return "The lord has pledged to oppose the proposal in council."; }
}
