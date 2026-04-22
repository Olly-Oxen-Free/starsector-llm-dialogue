package starlogue.provider;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.characters.PersonAPI;
import starlogue.action.StarlogueAction;
import starlogue.action.fleet.*;
import starlogue.engine.GameContext;
import starlogue.memory.MemoryEngine;
import starlogue.personality.PersonalityComposer;
import java.util.ArrayList;
import java.util.List;

public class FleetCaptainPlugin implements StarloguePlugin {

    @Override
    public boolean canEngage(SectorEntityToken entity) {
        if (!(entity instanceof CampaignFleetAPI)) return false;
        CampaignFleetAPI fleet = (CampaignFleetAPI) entity;
        return !fleet.isPlayerFleet()
            && fleet.getCommander() != null
            && fleet.getFaction() != null;
    }

    @Override
    public GameContext buildContext(SectorEntityToken entity) {
        CampaignFleetAPI fleet = (CampaignFleetAPI) entity;
        PersonAPI commander = fleet.getCommander();
        FactionAPI npcFaction = fleet.getFaction();
        FactionAPI playerFaction = Global.getSector().getPlayerFaction();
        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();

        GameContext ctx = new GameContext();
        ctx.entity = entity;
        ctx.person = commander;
        ctx.speakerName = commander.getNameString();
        ctx.fleet = fleet;
        ctx.npcFaction = npcFaction;
        ctx.playerFaction = playerFaction;
        ctx.repLevel = npcFaction.getRelationshipLevel(playerFaction.getId());
        ctx.repValue = npcFaction.getRelationship(playerFaction.getId());

        // Individual relationship: not directly on PersonAPI in this API version
        // Use faction relationship as proxy
        ctx.individualRel = ctx.repValue;

        ctx.npcFleetPoints = fleet.getFleetPoints();
        ctx.playerFleetPoints = playerFleet != null ? playerFleet.getFleetPoints() : 0;
        int denom = Math.max(ctx.npcFleetPoints, ctx.playerFleetPoints);
        ctx.strengthDelta = denom == 0 ? 0f
            : (float)(ctx.npcFleetPoints - ctx.playerFleetPoints) / denom;

        ctx.memoryScore = MemoryEngine.getScore(commander);
        ctx.playerHasBluffCredibility = MemoryEngine.getFactionScore(npcFaction) > -20f;

        if (playerFleet != null) {
            com.fs.starfarer.api.campaign.rules.MemoryAPI playerMem = playerFleet.getMemory();
            ctx.repGained30d = playerMem.contains("$starlogue_rep_gained_30d")
                ? playerMem.getFloat("$starlogue_rep_gained_30d") : 0f;
            ctx.repLost30d = playerMem.contains("$starlogue_rep_lost_30d")
                ? playerMem.getFloat("$starlogue_rep_lost_30d") : 0f;
        }

        return ctx;
    }

    @Override
    public List<StarlogueAction> getActions(GameContext ctx) {
        List<StarlogueAction> actions = new ArrayList<StarlogueAction>();
        // Combat resolution
        actions.add(new AttackAction());
        actions.add(new RetreatAction());
        actions.add(new StandDownAction());
        actions.add(new WarnOffAction());
        // Negotiation / transfers
        actions.add(new ExtortAction());
        actions.add(new PayTributeAction());
        actions.add(new TradeOfferAction());
        actions.add(new RansomCrewAction());
        // Diplomatic
        actions.add(new ShareIntelAction());
        actions.add(new RecruitAllyAction());
        actions.add(new CeasefireAction());
        // Relationship
        actions.add(new AdjustIndividualRelAction());
        actions.add(new AdjustFactionRelAction());
        return actions;
    }

    @Override
    public String getSystemPromptPreamble(GameContext ctx) {
        return PersonalityComposer.compose(ctx);
    }
}
