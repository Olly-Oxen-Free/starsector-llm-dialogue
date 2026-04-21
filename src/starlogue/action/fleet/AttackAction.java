package starlogue.action.fleet;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetAssignment;
import starlogue.action.StarlogueAction;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.memory.MemoryEvent;
import org.apache.log4j.Logger;
import java.util.Collections;
import java.util.Map;

public class AttackAction implements StarlogueAction {
    private static final Logger log = Logger.getLogger(AttackAction.class);

    @Override public String getId() { return "attack"; }

    @Override
    public String getDescription() {
        return "The NPC fleet declares hostility and moves to engage the player fleet in combat. "
             + "Use when the NPC chooses to fight. This ends the conversation.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Collections.emptyMap();
    }

    @Override public boolean isBluffable() { return true; }

    @Override
    public boolean isAvailable(GameContext ctx) {
        // Fleet that is already retreating or standing down cannot attack
        if (ctx.fleet == null) return false;
        // Very weak fleets vs strong player don't credibly attack unless reckless/aggressive personality
        // (bluff handles the personality gating)
        return true;
    }

    @Override
    public void execute(GameContext ctx, Map<String, Object> args) {
        if (ctx.fleet == null) return;
        ctx.fleet.clearAssignments();
        // INTERCEPT the player fleet — causes the fleet AI to pursue and engage
        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();
        ctx.fleet.addAssignment(FleetAssignment.INTERCEPT, playerFleet, 1f);
        MemoryEngine.recordEvent(ctx.person, MemoryEvent.HOSTILE_ACT, 1.0f);
        log.debug("Starlogue: attack declared by " + ctx.person.getNameString());
    }

    @Override public String narrativeNote() { return "They open fire. Battle stations!"; }
}
